# Contributing

`cloud-itonami-isic-9523` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development

This repo holds the business blueprint and operator contracts. See
`kotoba-lang/industry` for the technology-stack resolution.

```bash
clojure -M:test
clojure -M:lint
```

Keep changes small and include tests for any capability-layer change.

## Rules

- Do not commit real member/client/customer records, credentials, or personal data.
- Keep performing a repair or returning an item to the customer behind the Repair Shop Governor.
- Treat this vertical as high-risk: add tests for verification integrity,
  action gating and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
