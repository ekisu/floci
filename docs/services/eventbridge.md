# EventBridge

**Protocol:** JSON 1.1 (`X-Amz-Target: AmazonEventBridge.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateEventBus` | Create a custom event bus |
| `DeleteEventBus` | Delete an event bus |
| `DescribeEventBus` | Get event bus details |
| `UpdateEventBus` | Update event bus description, KMS key, dead-letter config, or log config |
| `ListEventBuses` | List all event buses |
| `PutRule` | Create or update a rule with a schedule or event pattern |
| `DeleteRule` | Delete a rule |
| `DescribeRule` | Get rule details |
| `ListRules` | List rules |
| `EnableRule` | Enable a disabled rule |
| `DisableRule` | Disable a rule |
| `PutTargets` | Add targets to a rule |
| `RemoveTargets` | Remove targets from a rule |
| `ListTargetsByRule` | List targets for a rule |
| `PutEvents` | Publish custom events to an event bus |
| `TestEventPattern` | Test whether a sample event matches a given pattern (no targets fired) |
| `ListTagsForResource` | - |
| `TagResource` | - |
| `UntagResource` | - |
| `PutPermission` | - |
| `RemovePermission` | - |
| `CreateArchive` | - |
| `DescribeArchive` | - |
| `UpdateArchive` | - |
| `DeleteArchive` | - |
| `ListArchives` | - |
| `CreateConnection` | Create a connection for API destinations (credential values are stored but never returned) |
| `DescribeConnection` | Get connection details with credential values stripped |
| `UpdateConnection` | Update connection description, auth type, or auth parameters |
| `DeleteConnection` | Delete a connection |
| `ListConnections` | List connections, optionally filtered by name prefix or state |
| `StartReplay` | - |
| `DescribeReplay` | - |
| `CancelReplay` | - |
| `ListReplays` | - |
<!-- floci:actions:end -->

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EVENTBRIDGE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a custom event bus
aws events create-event-bus \
  --name my-bus \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a rule matching a pattern
aws events put-rule \
  --name order-placed-rule \
  --event-bus-name my-bus \
  --event-pattern '{"source":["com.myapp"],"detail-type":["OrderPlaced"]}' \
  --state ENABLED \
  --endpoint-url $AWS_ENDPOINT_URL

# Add a Lambda target
aws events put-targets \
  --rule order-placed-rule \
  --event-bus-name my-bus \
  --targets '[{
    "Id": "process-order",
    "Arn": "arn:aws:lambda:us-east-1:000000000000:function:process-order"
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL

# Publish an event
aws events put-events \
  --entries '[{
    "Source": "com.myapp",
    "DetailType": "OrderPlaced",
    "Detail": "{\"orderId\":\"123\",\"amount\":99.99}",
    "EventBusName": "my-bus"
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Default Event Bus

EventBridge includes a default event bus (`default`) that accepts events from AWS services. Custom buses are for your own application events.

```bash
# List rules on the default bus
aws events list-rules --endpoint-url $AWS_ENDPOINT_URL

# Send to default bus
aws events put-events \
  --entries '[{"Source":"myapp","DetailType":"test","Detail":"{}"}]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## Event Bus Targets

A rule can target another event bus by ARN: the event is republished there, and that bus's own rules evaluate it and fan out normally. `Source`, `DetailType`, `Resources` and the originating `account`/`region` carry over, and each hop gets a new event id.

```bash
aws events put-targets \
  --rule order-placed-rule \
  --event-bus-name my-bus \
  --targets '[{
    "Id": "forward-to-domain-bus",
    "Arn": "arn:aws:events:us-east-1:000000000000:event-bus/domain-bus"
  }]' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## CloudWatch Logs Targets

A rule can target an existing [CloudWatch Logs](cloudwatch.md) log group. Use
the log-group ARN, without a log-stream suffix. A trailing `:*` is also accepted.
Floci resolves the destination from the ARN and requires its account to match
the owning rule's account.

```bash
aws logs create-log-group --log-group-name /aws/events/orders \
  --endpoint-url "$AWS_ENDPOINT_URL"

aws events put-targets \
  --rule order-placed-rule \
  --event-bus-name my-bus \
  --targets '[{
    "Id": "order-logs",
    "Arn": "arn:aws:logs:us-east-1:000000000000:log-group:/aws/events/orders"
  }]' \
  --endpoint-url "$AWS_ENDPOINT_URL"

aws logs filter-log-events --log-group-name /aws/events/orders \
  --endpoint-url "$AWS_ENDPOINT_URL"
```

By default, each matching event becomes a log entry containing the full event JSON,
with the event envelope's `time` converted to epoch milliseconds for the log timestamp.
An `InputTransformer` must produce an object with an ISO 8601 `timestamp` string and
a `message` string, for example:

```json
{
  "InputPathsMap": {"timestamp": "$.time", "message": "$.detail.message"},
  "InputTemplate": "{\"timestamp\":<timestamp>,\"message\":<message>}"
}
```

The message is stored as text, without JSON string delimiters. `Input` and `InputPath`
are unsupported for Logs targets. Floci accepts target registration, but rejects these
options, invalid Logs destination ARNs, cross-account Logs targets, and invalid
transformed payloads during delivery with a warning.

On AWS, configure a Logs resource policy granting `events.amazonaws.com`
`logs:CreateLogStream` and `logs:PutLogEvents`, and omit target `RoleArn`. See
[AWS resource-based permissions](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-use-resource-based.html#eb-cloudwatchlogs-permissions)
and [input transformation](https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-transform-target-input.html).
Floci stores resource policies but does not evaluate them on Logs writes, and its
target model ignores `RoleArn`. Delivery neither creates nor modifies policies.

Floci creates one `eventbridge/<uuid>` stream per delivery to avoid concurrent stream
creation and metadata-update races. Stream names and grouping do not reproduce AWS's
internal stream allocation. Missing log groups are never created implicitly.

## Current Behavior

- `PutEvents` reports success once the source bus accepts an event, so target delivery failures surface only as a `WARN` in the Floci logs.
- A `Detail` forwarded to an event bus must be a JSON object, as in AWS; anything else is dropped, including an `InputPath` selecting a scalar such as `$.detail.orderId` or an envelope carrying `"detail": null`.
- A bus ARN naming another account is forwarded under that account, so the target bus and its rules resolve there.
- CloudWatch Logs targets must belong to the receiving rule's account. A forwarded event keeps its originating account in the envelope, but can be delivered to Logs in the receiving bus's account. Direct cross-account Logs delivery is dropped with a warning.
- Target registration persists the owning account internally. Legacy stored Logs targets without this metadata must be registered again with `PutTargets` before delivery; Floci drops them with a warning rather than inferring ownership from the sender.
- Logs delivery uses the same synchronous, warning-only failure handling as other targets. Target retry policies and dead-letter delivery are not implemented by this path.
- An event is forwarded between buses only once, matching AWS: a bus that received an event from another bus does not forward it on to a third. The second hop is dropped with only a `WARN` rather than reported to the caller.
