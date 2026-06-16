## Summary

<!-- What changed and why. 2–5 sentences drawn from the diff. -->

## Related

Closes #

## What Changed

- [ ] Core server / networking (`server/`)
- [ ] RESP parser / encoder (`protocol/`)
- [ ] Command dispatcher / handlers (`command/`)
- [ ] Keyspace / data types (`store/`)
- [ ] Persistence — RDB / AOF (`persistence/`)
- [ ] Replication (`replication/`)
- [ ] Tests
- [ ] Build / Gradle config
- [ ] Documentation (`docs/`, `README.md`)

## Verification

```bash
./gradlew build
./gradlew test
# Manual redis-cli steps (if applicable):
redis-cli PING
```

## Breaking Changes

<!-- Yes / No. If yes, describe what breaks and the migration path. -->

## Follow-Up

<!-- Items intentionally deferred to a future issue. -->
