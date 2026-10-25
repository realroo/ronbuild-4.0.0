# RonAutoBuilder

Builder NPCs that construct schematics block by block, with separate survival and admin modes,
a hire menu and a built-in economy.

## Requirements
- Paper 1.21.1 or newer (up to the latest 26.x), Java 21+ (Java 25 on 26.x servers)
- Optional: Vault + any economy plugin (money), PlaceholderAPI (placeholders)

Builder NPCs are player-model NPCs with a skin on 1.21.9+; older servers get an armor-stand builder.

## Modes
| | Survival | Admin |
|---|---|---|
| Permission | `ronautobuilder.survival` (everyone) | `ronautobuilder.admin` (operators) |
| Menu | green Builder's Table | purple Control Panel |
| Settings | `survival.yml` | `admin.yml` |
| Schematics | `schematics/players/` | `schematics/admin/` |
| Materials | inventory, supply chest or paid contract | free, inventory or chest |

## Configuration files
`config.yml` shared settings - `survival.yml` - `admin.yml` - `economy.yml` - `hire.yml` - `shop-prices.yml`

## Economy (survival players only)
Turn it on in `economy.yml`. `currency.type: VAULT` uses the economy plugin Vault is connected to
(the console prints which one); `ITEM` pays with an item such as diamonds.

**Setup A - pay for labor, bring your own blocks.** `/ab supply inventory` or `chest`. The builders charge
`labor-per-block`, taken in batches while building. When the player runs out of funds the build pauses with
"Build paused: Insufficient funds! Deposit more money and run /ab resume".

**Setup B - turnkey contractor.** `/ab supply contract`. The builders bring the blocks. The price comes from
`shop-prices.yml` plus the labor fee. The player sees the total, confirms, and it is charged before the first block.
Cancelling refunds what was not used; starting the same build again only charges for the blocks still missing.

**Hiring.** `hire.yml` lists the price of 1-4 builders and of each speed (default: 1 builder, speed 5, free).
Players open `/ab hire` (or the Hire button) and the fee is charged once per build.

## Commands
Everything is available from the menu: `/ab`. Aliases: `/rab`, `/autobuilder`, `/constructor`.

Players: `list`, `load`, `origin [look]`, `rotate`, `preview [outline|ghost|off]`, `build`, `rebuild`, `pause`,
`resume`, `cancel`, `status`, `undo`, `materials`, `supply <inventory|chest|contract>`, `chest`,
`hire [builders <n> | speed <n>]`, `confirm`, `deny`.

Admins: `admin`, `survival`, `supply free`, `build ignoreair`, `pos1`, `pos2`, `save <name> [players]`,
`skin <player|reset>`, `reload`, `economy`, `npc create|remove|list|move|tp|skin|command`.

Build options: `builders=N speed=N pattern=P mode=M ignorewater`
Patterns: up, down, random, outward, left, right, north, south, east, west. Modes: shared, opposite, split.

## Placeholders
`%autobuilder_status%`, `%autobuilder_progress%`, `%autobuilder_active_builders%`,
`%autobuilder_placed%`, `%autobuilder_total%`, `%autobuilder_schematic%`

## Data files
`jobs.yml` stores running builds (saved every 30 seconds and on shutdown) so they resume after a restart.
`npcs.yml` stores NPCs created with `/ab npc`.
