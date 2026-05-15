# Contributing

This is primarily a research codebase — contributions are welcome but
should fit the platform-as-testbed thesis described in
[`docs/research-design.md`](docs/research-design.md).

## Development setup

1. Java 21 (Temurin recommended)
2. Docker (for Testcontainers and `docker compose`)
3. `make` and `bash`
4. Optional: Docker Desktop Kubernetes, `helm`, `kubectl` for the Kubernetes path

## Running tests

```bash
./mvnw verify              # unit + property + arch tests
./mvnw verify -DskipITs=false   # add integration tests (Testcontainers)
make mutation              # Pitest mutation coverage
```

## Code style

- **Domain code is pure Java**. No Spring annotations, no JPA, no
  reflection magic. ArchUnit will fail your build if you cross this
  line.
- **Application services orchestrate, they do not contain business
  logic**. Business rules live in the aggregate.
- **Domain events are facts in past tense**. `CaseEscalated`, not
  `EscalateCase`.
- **Add tests at the lowest layer that catches the bug**. A new
  invariant test goes in `domain/`, not `application/`.

## Pull request checklist

- [ ] All tests green (`./mvnw verify`)
- [ ] Mutation coverage on touched domain code is ≥ 85%
- [ ] If you added a new domain event, the consumer side is updated too
- [ ] If you changed business behaviour,
      [`docs/BUSINESS_LOGIC.md`](docs/BUSINESS_LOGIC.md) is updated
- [ ] If you changed structure or layering,
      [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) is updated
