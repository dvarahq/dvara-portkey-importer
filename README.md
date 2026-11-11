# dvara-portkey-importer: move a Portkey gateway config to DVARA

It reads a Portkey gateway config, the JSON you send in `x-portkey-config` or keep in your repo for the open-source
Portkey gateway, and writes a `gateway.yaml` for the DVARA open-source gateway. It also writes a report that says,
for every part of your config, whether it maps cleanly, maps with a difference, or has no equivalent, and why.

```bash
jbang portkey-to-dvara@dvarahq config.json -o dvara-import/
```

That needs only [JBang](https://www.jbang.dev/download/). It fetches the released jar and, if you don't have Java 25,
downloads it the first time. To keep it as a command, run `jbang app install portkey-to-dvara@dvarahq`, then
`portkey-to-dvara config.json`. Without JBang, download `dvara-portkey-importer.jar` from the
[latest release](https://github.com/dvarahq/dvara-portkey-importer/releases/latest) and run
`java -jar dvara-portkey-importer.jar config.json -o dvara-import/` on Java 25.

A config saved only in Portkey's app (one you reach by its `pc-...` id) has to be in a file first: open it on the
Configs page and copy its JSON, or fetch it from Portkey's admin API. The importer reads the admin API's response
as it is.

```
Wrote gateway.yaml, import-report.md, dvara-import.properties to dvara-import
  1 configs, 3 providers, 1 routes, 0 policies
  Maps cleanly: 6
  Maps, with a difference: 6
  No equivalent: 1
  You need to: 6
  What DVARA adds: 7
Read dvara-import/import-report.md before you start the gateway.
```

- It reads only the file or folder you name. It opens no network connection and writes only into the output folder.
- **It never copies a secret.** An `api_key` or AWS key written in your config is not copied: the output names an
  environment variable instead, and the report tells you to set it and rotate the key. A value written as
  `${NAME}` or `$NAME` keeps that variable. A user, password or query string in a `custom_host` is not copied
  either: the URL is written without them.
- **A virtual key or Model Catalog provider (`@slug`) stays in Portkey's vault.** The importer cannot read it. It
  maps the provider, names an environment variable for the key, and tells you to copy the key from Portkey.
- It does not replace files already in the output folder unless you pass `--force`.

Checked against the Portkey gateway source at v1.15.2 (main at `669825c`, 25 May 2026) and Portkey's docs, on
11 Nov 2026.

Apache License 2.0.

## Input

| You pass | What it reads |
|---|---|
| `config.json` with one config object | That config |
| `configs.json` with a JSON array of configs | Each one, named `configs-1`, `configs-2`, ... |
| A folder | Every `*.json` file in it (not subfolders), each named after its file |
| Portkey's admin API response (`{"data": {"config": ...}}`) | The config inside it |

Keys may be in snake_case or camelCase (`before_request_hooks` or `beforeRequestHooks`), as Portkey reads both.
The older form, `mode` and `options`, is read as `strategy.mode` and `targets`.

## What it writes

| File | What it is |
|---|---|
| `gateway.yaml` | Providers, a workspace, routes and policies, in the open gateway's format. Start the gateway with `DVARA_CONFIG_FILE` pointing at it. |
| `import-report.md` | The diff report: what you need to do, what has no equivalent and why, what changed, what maps cleanly, and what DVARA adds. |
| `dvara-import.properties` | Retries and timeouts, which `gateway.yaml` cannot carry. Only written when your config sets them. Pass it with `--spring.config.additional-location=file:dvara-import.properties`. |

`gateway.yaml` has no API keys. The gateway starts without one and refuses every call until you add one. Mint a key
with the gateway's own `--generate-key` and paste the entry it prints.

## How a Portkey config maps

Portkey applies a config to the requests that send it. The gateway picks a route by the model a request names. So:

- **One config** becomes a route for every model (`model: "*"`). Callers keep their model name; when the config
  sets `override_params.model`, the provider is sent that model instead.
- **Several configs** each become a route named after the config. Callers send `model: "<config name>"` instead of
  the `x-portkey-config` header. A config that sends the caller's own model can only be the route for every model,
  so only one such config is served. Policy `portkey-served-models` then refuses any model no route names.

| Portkey | DVARA |
|---|---|
| A target's `provider` and key | A provider entry, key from an environment variable |
| `override_params.model` | The route's `pinned_model_version`: the model the provider is sent |
| `strategy.mode: single` | The first target |
| `strategy.mode: fallback` | The route's `fallbacks` chain, in order, at most 5 |
| `strategy.mode: loadbalance` with `weight` | A `weighted` route, when the targets are sent the same model |
| `loadbalance` targets sent different models | The first model is served; the others become its fallbacks |
| Nested targets | Flattened: a balanced group's targets share the traffic, then every backup in order |
| `conditional` on `params.model` (`$eq`, `$in`, or a plain value) | A route for each model name, sending that target's model |
| `conditional` `default` | The route for every model no other route matches |
| `custom_host` (on the target or a group above it) | The provider's `base_url`, without credentials or query |
| Azure `resource_name`, `deployment_id` | `base_url` `https://<resource>.openai.azure.com/openai`, model `azure/<deployment>` |
| Bedrock `aws_access_key_id`, `aws_secret_access_key`, `aws_region` | `access_key`, `secret_key` from variables; `region` |
| `retry.attempts` | `dvara.llm-gateway.resilience.retry.max-attempts` (attempts + 1), or per provider when targets differ |
| `request_timeout` | `dvara.llm-gateway.resilience.timeout.chat-timeout-ms`, or per provider when targets differ |
| PII checks (`portkey.pii`, `patronus.pii`, `pangea.pii`, `promptfoo.pii`, `azure.pii`, `qualifire.pii`, `patronus.phi`) | The gateway's own PII detection: `pii.action` BLOCK when the hook denies, REDACT when it redacts, LOG otherwise; `pii.scan-responses` for an output check |
| Prompt-injection checks (`azure.shieldPrompt`, `qualifire.promptInjections`, `promptfoo.guard`) | The gateway's own guardrail: `guardrail.action` BLOCK when the hook denies, LOG otherwise |
| `default.modelwhitelist` that denies | A policy model allowlist (or denylist, with `not`) |

A check denies only when it runs in line (`async` not true) with `deny: true`, as in Portkey.

### Providers

| Portkey | DVARA provider | Model the gateway is sent |
|---|---|---|
| `openai` | `openai` | `gpt-4o` |
| `openai` with `custom_host` | `openai`, at that `base_url` | the model id |
| `azure-openai` | `azure-openai` | `azure/<deployment_id>` |
| `anthropic` | `anthropic` | `claude-sonnet-4-5` |
| `google` | `gemini` | `gemini-2.5-flash` |
| `bedrock` | `bedrock` | `bedrock/<model id>` |
| `mistral-ai` | `mistral` | `mistral-large-latest` |
| `cohere` | `cohere` | `command-r-plus` |
| `groq` | `groq` | `groq/<model>` |
| `deepseek` | `deepseek` | `deepseek-chat` |
| `x-ai` | `grok` | `grok-4` |
| `dashscope` | `qwen` | `qwen-plus` |
| `moonshot` | `moonshot` | `kimi-k2-0711-preview` |
| `zhipu` | `chatglm` | `glm-4.6` |
| `ollama` | `ollama` | `ollama/<model>` |

Any other provider (`vertex-ai`, `azure-ai`, `z-ai`, `together-ai`, `openrouter` and the rest) is not served, and
the report says so for each target. A server that speaks the OpenAI API can be written as `openai` with its
`custom_host`.

A `virtual_key` or `@slug` names a provider kept in Portkey's cloud. When the config does not also say `provider`,
the importer infers it from the name (`@openai-prod`, `anthropic-vk-1a2b`) or the model (`gpt-*`, `claude-*`), and
the report asks you to check it. When it can't tell, the target is not served.

A target is never pointed at the provider's public API when it named a server of its own. If its `custom_host`
cannot be read, is not http(s), or names a variable the gateway cannot read, the target is not served and the
report says so under "You need to".

### What does not carry over

The report names each one. The main ones:

- **Portkey's headers.** `x-portkey-api-key`, `x-portkey-config`, `x-portkey-provider`, `x-portkey-virtual-key`,
  `x-portkey-metadata` and the rest are not read by the gateway. Clients stop sending them and send a DVARA key
  as `Authorization: Bearer <key>`.
- **One endpoint per provider.** The open gateway has one OpenAI, one Anthropic and so on. A second target of the
  same provider with a different key, vault entry or host is not served. Load balancing two OpenAI keys becomes
  one. (A workspace can bring its own provider key under `credentials`.)
- **Conditions on metadata, other parameters or the path.** Their target gets a route of its own name, and the
  report tells you which callers should send it. Without that, they go to the default target.
- **Fallback and retry status codes.** The gateway fails over and retries on server errors, timeouts, rate limits
  and open circuits; the list cannot be set, and a request the provider refuses (4xx) does not fail over.
- **Caching** (simple and semantic), `forward_headers`, `default_params`, `drop_params`, `override_params` other
  than `model`, and `strict_open_ai_compliance`. An overridden `max_tokens` is listed under "You need to", with
  the policy rule that keeps a cap.
- **Guardrail checks the gateway has no equivalent for** (regex, word lists, JSON schema, webhooks, vendor
  scanners). One that blocked requests is listed under "You need to": those requests now go through. Guardrails
  referenced by id live in Portkey's cloud and are listed too.
- **Guardrails set on one target.** The gateway sets them per workspace, so they apply to every request.
- **Anything in Portkey's cloud**: virtual keys and their secrets, saved guardrails, prompts, budgets, rate limits
  and logs. Only the config file is read.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Written |
| 1 | The input could not be read, is not JSON, or is not a Portkey config |
| 2 | Usage error |
| 3 | A file in the output folder would be replaced; pass `--force` |

## Build and test

```bash
./mvnw verify
java -jar target/dvara-portkey-importer.jar --help
```

The project stands alone: no parent and no runtime dependency (it reads JSON with a small reader of its own). The
tests check the output with the open gateway's own code, `dvara-gateway-autoconfigure` and `dvara-gateway-policy`
as test dependencies: each fixture under `src/test/resources/fixtures` is imported and then loaded and validated by
the gateway's `gateway.yaml` loader, served by its configuration store, routed by its routing engine, its policies
compiled and its properties bound. The fixtures follow Portkey's documented examples.

The tests use the latest open release by default. To test against a newer snapshot:

```bash
./mvnw verify -Pdvara-snapshot -Ddvara.version=1.14.0-SNAPSHOT
```
