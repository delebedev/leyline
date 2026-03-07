# Carousel_GetCarouselItems Handler

## Goal

Implement `Carousel_GetCarouselItems` (CmdType 704) as a real server handler. The server owns the carousel — decides what banners show, filters by client platform/region, and references events from `EventRegistry`.

## Context

Client sends:
```json
{"Region":"GB","Language":"en-US","Platform":"OSXPlayer"}
```

Currently unhandled — home screen carousel is blank in stub mode.

## Design

Follow the **EventRegistry pattern** — code-generated JSON from typed data classes. Not GoldenData (frozen blobs). We are building a real server, not replaying captures.

### CarouselRegistry

New file: `frontdoor/service/CarouselRegistry.kt`

```kotlin
data class CarouselItem(
    val id: String,
    val type: String,              // "EventBanner", "StoreBanner", "NewsBanner"
    val priority: Int,
    val title: String,             // loc key or display string
    val imageUri: String,          // CDN asset URL or empty
    val actionType: String,        // "OpenEvent", "OpenStore", "OpenUrl"
    val actionTarget: String,      // event InternalEventName, store path, or URL
    val platforms: List<String> = listOf("*"),
    val regions: List<String> = listOf("*"),
)

object CarouselRegistry {
    val items: List<CarouselItem> = listOf(
        CarouselItem(
            id = "standard-ranked",
            type = "EventBanner",
            priority = 100,
            title = "PlayBlade/FindMatch/Blade_Standard_Ladder",
            imageUri = "",
            actionType = "OpenEvent",
            actionTarget = "Ladder",
        ),
        CarouselItem(
            id = "bot-match",
            type = "EventBanner",
            priority = 50,
            title = "Events/Event_Title_AIBotMatch",
            imageUri = "",
            actionType = "OpenEvent",
            actionTarget = "AIBotMatch",
        ),
        // Add more as we stub more events
    )

    fun toJson(region: String, platform: String): String {
        // Filter items by region/platform, build JSON
    }
}
```

### Handler wiring

```kotlin
// FrontDoorHandler.kt
704 -> { // Carousel_GetCarouselItems
    val req = kotlinx.serialization.json.Json.parseToJsonElement(jsonPayload).jsonObject
    val region = req["Region"]?.jsonPrimitive?.content ?: ""
    val platform = req["Platform"]?.jsonPrimitive?.content ?: ""
    log.info("Front Door: CarouselItems (region={}, platform={})", region, platform)
    writer.sendJson(ctx, txId, CarouselRegistry.toJson(region, platform))
}
```

## Response shape

Needs confirmation from a real S2C capture (blocked on #21). Inferred structure:

```json
{
  "CarouselItems": [
    {
      "Id": "standard-ranked",
      "Type": "EventBanner",
      "Priority": 100,
      "Title": "PlayBlade/FindMatch/Blade_Standard_Ladder",
      "ImageUri": "",
      "Action": {
        "ActionType": "OpenEvent",
        "EventName": "Ladder"
      },
      "StartTime": "2025-01-01T00:00:00Z",
      "EndTime": "2099-01-01T00:00:00Z",
      "Platform": ["*"],
      "Region": ["*"]
    }
  ]
}
```

**Field names are guesses** — must be validated against a real capture once #21 is fixed. The implementation should be easy to adjust once we have the real shape.

## Constraints

- Banners MUST only reference events that exist in `EventRegistry`. Dangling references = client errors.
- Platform/region filtering: `"*"` = all. Match against request fields.
- Keep it minimal — start with 2-3 banners for events we already serve. Expand as we add events.

## Tests

`CarouselRegistryTest` (UnitTag):
- `toJson` returns valid JSON with `CarouselItems` array
- Platform filtering: item with `["OSXPlayer"]` excluded when platform is `"WindowsPlayer"`
- Region filtering: item with `["US"]` excluded when region is `"GB"`
- All `actionTarget` values exist in `EventRegistry`

## Dependencies

- **#21** — need a real S2C capture to confirm field names. Until then, implement with best-guess shape; adjust when data arrives.
- **EventRegistry** — banners reference events by `InternalEventName`.
