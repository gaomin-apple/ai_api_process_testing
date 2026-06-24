# AI Java Project Flow Generation

AFT Studio can scan a local Java project and ask an OpenAI-compatible chat
completion model to infer API call relationships as flow nodes and directed
edges. The generated result is saved as a normal AFT flow and can be edited,
saved, and executed in the existing canvas.

Default provider:

- Base URL: `https://api.deepseek.com/v1`
- Model: `deepseek-chat`

Server environment variables:

```powershell
$env:AFT_LLM_API_KEY = "sk-..."
$env:AFT_LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:AFT_LLM_MODEL = "deepseek-chat"
```

The UI also supports request-level overrides for Base URL, model, and API key.
The API must be compatible with OpenAI Chat Completions at
`/v1/chat/completions`.

Usage:

1. Click the `AI Generate Flow` toolbar action.
2. Enter the local Java project directory.
3. Confirm or override the LLM settings.
4. Generate the flow and review the canvas before running it.

If the project already has OpenAPI endpoints imported, the model selects from
those endpoint IDs. If not, the scanner extracts Spring MVC annotations such as
`@RequestMapping`, `@GetMapping`, and `@PostMapping`, creates endpoint entries,
and then asks the model to order them into a flow.
