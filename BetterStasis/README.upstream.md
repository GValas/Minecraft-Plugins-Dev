# BetterStasis

A Minecraft plugin for Paper 1.21+ that implements a pearl stasis chamber system.

## Features

- **Bind ender pearls to fishing rods** - Shift + Right-Click with a fishing rod to bind the closest ender pearl
- **Teleport on demand** - Right-Click (reel) the bound rod to teleport to the pearl location
- **Server-friendly** - Pearl entities disappear when bound, no performance impact
- **Reusable** - Rods don't break, take damage, or have cooldowns
- **No damage** - Teleporting doesn't hurt the player
- **No permissions** - Everyone can use the feature by default

## Installation

1. Download `BetterStasis-1.0.0.jar` from [Releases](https://modrinth.com/plugin/betterstasis)
2. Place the jar in your server's `plugins/` folder
3. Restart or reload your server
4. Configure settings in `plugins/PearlStasis/config.yml` (optional)

## How to Use

1. **Throw an ender pearl** somewhere you want to teleport to later
2. **Shift + Right-Click** with a fishing rod to bind the pearl
   - By default, the rod looks completely vanilla (no visible information)
   - Set `show-information: true` in config to see coordinates and enchant glow
   - The pearl entity will disappear
3. **Right-Click (reel)** the bound rod anytime to teleport to that location
   - By default, the binding persists for multiple uses
   - Set `clear-after-teleport: true` in config to clear binding after each use
   - Set `use-durability: true` in config to make the rod take damage

## Configuration

Edit `plugins/PearlStasis/config.yml`:

```yaml
settings:
  search-radius: 8.0          # Max distance to find pearls when binding
  min-pearl-distance: 2.0     # Min distance to prevent instant pickup

  feedback:
    particles: true           # Show particle effects
    sounds: true              # Play sound effects
    messages: true            # Send chat messages

  rod-appearance:
    show-information: false   # Show coordinates and glow on bound rods
    use-durability: false     # Enable rod durability damage when teleporting

  player-binding:
    clear-after-teleport: true    # Clear binding after each use

  teleport:
    check-safe-location: true      # Verify destination is safe
    prevent-suffocation: true      # Don't teleport into solid blocks
    cancel-if-unsafe: true         # Cancel unsafe teleports
```

## Technical Details

- **Platform**: Paper 1.21+
- **Java**: 21
- **Storage**: PersistentDataContainer (survives server restarts, item drops, chest storage)
- **Performance**: Event-driven, no ticking tasks, minimal overhead
- **Compatibility**: Works across all 1.21.x versions

## Support

Report issues at: https://discord.gg/CUQdkPzGKb
