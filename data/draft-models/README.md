# Draft Models

Bundled local draft-pick model artifacts used when `draft.picker = "model"`.

Each set directory contains:

- `weights.json.gz` - compressed model weights exported for in-process inference
- `card_meta.json` - card-name index for the same model

If a set directory is missing or cannot be loaded, drafts use Forge's default picker.

Current bundled weights:

- `fdn/weights.json.gz` SHA-256 `f975cc3c6509bd0f585b36f34b7b1a7b3822394195b00292b7ddee5fba780f24`
- `eoe/weights.json.gz` SHA-256 `b851d346cfb968e9cd015cb02f7c6047d18fa8c37384b8e8e6a812a5e4664f19`

To replace a model, export a compatible `weights.json`, gzip it, copy the matching `card_meta.json`, then update the checksums above.
