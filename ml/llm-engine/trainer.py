"""
LoRA fine-tuning for Qwen2.5 on AML incident data.

Uses PEFT + TRL SFTTrainer. Designed to run as a background thread inside the
llm-engine FastAPI service. Defaults to Qwen2.5-0.5B-Instruct (CPU-friendly);
set TRAINING_MODEL=Qwen/Qwen2.5-3B-Instruct for the full model (GPU recommended).

Outputs a LoRA adapter to ADAPTER_DIR that the inference path can load.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from threading import Event
from typing import Any

logger = logging.getLogger("llm-engine.trainer")

TRAINING_MODEL = os.getenv("TRAINING_MODEL", "Qwen/Qwen2.5-0.5B-Instruct")
ADAPTER_DIR    = Path(os.getenv("ADAPTER_DIR", "/tmp/qwen-lora-adapter"))
DATA_DIR       = Path(os.getenv("TRAINING_DATA_DIR", "/tmp/qwen-training-data"))

MIN_EXAMPLES   = int(os.getenv("MIN_TRAINING_EXAMPLES", "5"))
LORA_R         = int(os.getenv("LORA_R", "8"))
LORA_ALPHA     = int(os.getenv("LORA_ALPHA", "16"))
EPOCHS         = int(os.getenv("TRAIN_EPOCHS", "3"))
BATCH_SIZE     = int(os.getenv("TRAIN_BATCH_SIZE", "1"))
GRAD_ACCUM     = int(os.getenv("TRAIN_GRAD_ACCUM", "4"))
LEARNING_RATE  = float(os.getenv("TRAIN_LR", "2e-4"))


class TrainingStatus(str, Enum):
    IDLE      = "IDLE"
    RUNNING   = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED    = "FAILED"


@dataclass
class TrainingState:
    status:         TrainingStatus = TrainingStatus.IDLE
    startedAt:      str | None     = None
    completedAt:    str | None     = None
    trainingSamples: int           = 0
    trainLoss:      float | None   = None
    error:          str | None     = None
    adapterReady:   bool           = False
    lossHistory:    list[float]    = field(default_factory=list)


def _format_example(ex: dict) -> list[dict]:
    """Format an incident+outcome into a Qwen chat message list."""
    aml_risk = (
        "CRITICAL" if ex["anomalyScore"] > 0.9
        else "HIGH"    if ex["anomalyScore"] > 0.75
        else "MEDIUM"  if ex["anomalyScore"] > 0.5
        else "LOW"
    )
    expected_output = json.dumps({
        "explanation":    ex.get("explanation") or (
            f"Service {ex['service']} experienced anomaly score {ex['anomalyScore']:.3f}. "
            f"Outcome: {ex['outcome']}."
        ),
        "rootCause":      ex.get("rootCause") or "unknown",
        "recommendation": ex.get("actionTaken") or (
            "Scale out replicas" if ex["anomalyScore"] > 0.75 else "Monitor closely"
        ),
        "amlRisk":        aml_risk,
        "confidence":     0.90,
    }, ensure_ascii=False)

    return [
        {
            "role":    "system",
            "content": (
                "You are an expert AML (Anti-Money Laundering) and AIOps analyst. "
                "Analyze incidents and respond with a JSON object containing: "
                "explanation, rootCause, recommendation, amlRisk (LOW/MEDIUM/HIGH/CRITICAL), confidence (0-1)."
            ),
        },
        {
            "role":    "user",
            "content": (
                f"Analyze this AIOps incident:\n"
                f"Service: {ex['service']}\n"
                f"Anomaly Score: {ex['anomalyScore']:.4f}\n"
                f"Root Cause Signal: {ex.get('rootCause') or 'unknown'}\n"
                f"Outcome recorded: {ex['outcome']}\n\n"
                f"Return JSON only."
            ),
        },
        {
            "role":    "assistant",
            "content": expected_output,
        },
    ]


def run_lora_training(examples: list[dict], state: TrainingState, stop: Event) -> None:
    """
    Blocking LoRA fine-tuning call. Runs in a daemon thread.
    Mutates `state` in-place to report progress.
    """
    from datetime import datetime, timezone

    state.status         = TrainingStatus.RUNNING
    state.startedAt      = datetime.now(timezone.utc).isoformat()
    state.trainingSamples = len(examples)
    state.error          = None
    state.lossHistory    = []

    if len(examples) < MIN_EXAMPLES:
        state.status = TrainingStatus.FAILED
        state.error  = f"Need at least {MIN_EXAMPLES} examples (got {len(examples)})"
        return

    try:
        import torch
        from datasets import Dataset
        from peft import LoraConfig, TaskType, get_peft_model
        from transformers import (AutoModelForCausalLM, AutoTokenizer,
                                  TrainingArguments)
        from trl import SFTTrainer, DataCollatorForCompletionOnlyLM

        logger.info("Loading tokenizer from %s", TRAINING_MODEL)
        tokenizer = AutoTokenizer.from_pretrained(
            TRAINING_MODEL,
            trust_remote_code=True,
            padding_side="right",
        )
        if tokenizer.pad_token is None:
            tokenizer.pad_token = tokenizer.eos_token

        logger.info("Loading model (CPU float32)…")
        model = AutoModelForCausalLM.from_pretrained(
            TRAINING_MODEL,
            torch_dtype=torch.float32,
            trust_remote_code=True,
        )
        model.config.use_cache = False

        lora_cfg = LoraConfig(
            task_type    = TaskType.CAUSAL_LM,
            r            = LORA_R,
            lora_alpha   = LORA_ALPHA,
            lora_dropout = 0.05,
            target_modules = ["q_proj", "v_proj", "k_proj", "o_proj"],
            bias         = "none",
        )
        model = get_peft_model(model, lora_cfg)
        model.print_trainable_parameters()

        # Tokenize using the Qwen chat template
        texts = []
        for ex in examples:
            msgs = _format_example(ex)
            text = tokenizer.apply_chat_template(
                msgs,
                tokenize=False,
                add_generation_prompt=False,
            )
            texts.append(text)

        dataset = Dataset.from_dict({"text": texts})

        ADAPTER_DIR.mkdir(parents=True, exist_ok=True)

        # Custom callback to track loss + support early stopping
        from transformers import TrainerCallback

        class _ProgressCallback(TrainerCallback):
            def on_log(self, args, tr_state, control, logs=None, **kwargs):
                if logs and "loss" in logs:
                    state.lossHistory.append(round(logs["loss"], 5))
                    logger.info("Epoch %.2f  loss=%.5f", tr_state.epoch or 0, logs["loss"])
            def on_step_end(self, args, tr_state, control, **kwargs):
                if stop.is_set():
                    control.should_training_stop = True

        train_args = TrainingArguments(
            output_dir               = str(ADAPTER_DIR),
            num_train_epochs         = EPOCHS,
            per_device_train_batch_size = BATCH_SIZE,
            gradient_accumulation_steps = GRAD_ACCUM,
            learning_rate            = LEARNING_RATE,
            logging_steps            = 1,
            save_strategy            = "epoch",
            fp16                     = False,
            bf16                     = False,
            report_to                = "none",
            dataloader_num_workers   = 0,
            no_cuda                  = True,
        )

        trainer = SFTTrainer(
            model           = model,
            args            = train_args,
            train_dataset   = dataset,
            dataset_text_field = "text",
            tokenizer       = tokenizer,
            max_seq_length  = 512,
            callbacks       = [_ProgressCallback()],
        )

        result = trainer.train()
        trainer.save_model(str(ADAPTER_DIR))
        tokenizer.save_pretrained(str(ADAPTER_DIR))

        state.trainLoss    = round(result.training_loss, 5)
        state.adapterReady = True
        state.status       = TrainingStatus.COMPLETED
        state.completedAt  = datetime.now(timezone.utc).isoformat()
        logger.info("LoRA training complete — loss=%.5f adapter=%s",
                    state.trainLoss, ADAPTER_DIR)

    except Exception as exc:
        logger.error("Training failed: %s", exc, exc_info=True)
        state.status = TrainingStatus.FAILED
        state.error  = str(exc)
        state.completedAt = __import__("datetime").datetime.now(
            __import__("datetime").timezone.utc
        ).isoformat()
