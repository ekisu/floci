# Deterministic Lambda names (local fork)

Set `FLOCI_CLOUDFORMATION_DETERMINISTIC_LAMBDA_NAMES=true` to enable predictable,
AWS-shaped generated Lambda names. The default is false. Native templates stay
unchanged, including explicit `FunctionName` properties.

Before creating a producer stack, query its final Lambda identity:

```sh
curl --get http://localhost:4566/_floci/cloudformation/lambda-name \
  --data-urlencode accountId=000000000000 \
  --data-urlencode region=us-east-1 \
  --data-urlencode rootStack=ProducerStack \
  --data-urlencode logicalPath=NestedStackLogicalId/FunctionLogicalId \
  --data-urlencode generation=0
```

The JSON response contains `functionName`, `functionArn`, `generation`,
`rootStack`, and `logicalPath`. For a top-level function the path is just its
logical ID. For an explicitly named function, also pass `explicitName` with the
resolved native template value. This endpoint is an opt-in local fork extension,
not an AWS API. It is unavailable when deterministic naming is disabled.

The endpoint and CloudFormation share the same name generator. The suffix hashes
account, region, root stack, complete nested logical path and resource generation.
Names fit Lambda's 64-character limit and have the shape `stack-logical-suffix`.
Use separate accounts, regions or root stack names for separate environments.

This is an immutable identity query, not a resource allocator: querying creates
no Lambda, registry entry or cleanup obligation. Callers may preseed SSM with the
returned final ARN, then compare the deployed physical ID with that reservation.
An existing function occupying a generated identity causes creation to fail;
CloudFormation never adopts it in this mode. Explicit names remain authoritative.

Generation starts at zero and is persisted with CloudFormation resource metadata.
Ordinary updates preserve the physical ID. Replacements increment generation;
failed creates do not commit a new generation. Nested stack updates reuse their
resource state. Stack deletion owns Lambda cleanup. Recreating a deleted stack
starts at zero, so a retained Lambda occupying that name must be explicitly
handled before recreation. Queries do not infer the current generation: obtain
it from the persisted resource metadata when planning replacements, rather than
assuming zero. The existing CloudFormation rollback semantics still apply.
