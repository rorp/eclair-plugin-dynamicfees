## Dynamicfees eclair plugin

This is a plugin for [eclair](github.com/ACINQ/eclair) to adjust your channel relay fees dynamically according to
the channel balance. The plugin conceptually defines 3 states in which the channel [balance] can be and a *multiplier*
associated to each state, the multiplier will be used to compute the new relay fees.

- Depleted: when 'toLocal' becomes too little the channel is considered depleted
- Saturated: when 'toLocal' becomes too large the channel is considered saturated
- Balanced: when the channel is not depleted nor saturated then it's balanced, no multiplier applied

The goal of the plugin is to keep the user's channel balanced by incentivizing the usage of depleted channels and
disincentivizing the usage of saturated channels, with this strategy the channels naturally tend to stay in a balanced
state. Note that if all nodes on the network apply this strategy all the users would benefit from a more balanced network.
The plugin works by intercepting the relayed payments and creates a new `channel_update` **if and only if** the channel is
transitioning from a state to another (i.e going *balanced* -> *depleted*), the plugin will *ignore* sent/received payments because they are user initiated. The plugin will apply the multiplier only to the `fee_proportional` value of the relay fee and use the **configured value** in `eclair.fee-proportional-millionths` as basepoint for the multiplication. This means that a manually updated relay fee will be overridden by the plugin once there is a relayed payments that make the channel transition to a new state.

### Installation
The plugin can be built locally, it's a fat jar that must be passed as argument to eclair when it's launched, see the [instructions](https://github.com/ACINQ/eclair#plugins).

First you need to build Eclair

```bash
git clone https://github.com/ACINQ/eclair.git

cd eclair/

git checkout v0.12.0

mvn install -DskipTests=true
```

Then build the plugin
```bash
git clone https://github.com/rorp/eclair-plugin-dynamicfees.git

cd eclair-plugin-dynamicfees/

mvn package
```

The last `mvn` command will put the plugin's JAR file into `target` directory.

### Usage
Once the plugin is configured and loaded it doesn't need any further input from the user, below there is a breakdown of how the plugin works:
1) for each relayed payment retrieve the data for the channels involved (in/out)
2) for each channel involved, if the channel is not blacklisted OR if the whitelist is non-empty and the channel is whitelisted
3) compute the channel balance status
4)
   - if the balance is below the depleted threshold compute the new fee according to depleted multiplier
   - if the balance is above the saturated threshold compute the new fee according to saturated multiplier
   - if the balance is above depleted and below saturated threshold use multiplier 1x
5) create a candidate channel_update using the new fee
6)
   - if the previous channel_update contains the same fees as the candidate **do not** broadcast it
   - if the previous channel_update contains different fees from the candidate **do** broadcast it


### Configuration
Users MUST define a configuration section specifying the chosen values for their depleted/saturated thresholds and their relative or fixed fee adjustment. The plugin supports two ways to set the fee for each state:

- **Multiplier**: The fee will be calculated as `fee-proportional-millionths * multiplier`, where `fee-proportional-millionths` can be set per plugin using the `dynamicfees.fee-proportional-millionths` option (see below). If not set, the default value from your eclair node config is used.
- **Amount**: The fee will be set to a fixed value (in millionths). You must specify either `multiplier` or `amount` for each state, but not both.

#### Per-node configuration
You can override the fee settings for specific nodes using the `dynamicfees.per-node` section. This allows you to specify custom thresholds, multipliers, or amounts for channels with a given remote node public key.

- The key must be the remote node's public key in hex format (compressed, 33 bytes, e.g. `02...`).
- The value is a config block with the same structure as the top-level dynamicfees section.
- If a channel's remote node is not listed in `per-node`, the global config is used.

##### Example per-node configuration

```hocon
 eclair.dynamicfees.depleted.threshold = 0.3
 eclair.dynamicfees.depleted.multiplier = 0.6
 eclair.dynamicfees.saturated.threshold = 0.8
 eclair.dynamicfees.saturated.amount = 2000

 # Per-node overrides
 eclair.dynamicfees.per-node {
   "02abcdef...": {
     depleted.threshold = 0.2
     depleted.multiplier = 0.9
     saturated.threshold = 0.7
     saturated.multiplier = 1.5
   }
   "03deadbeef...": {
     depleted.threshold = 0.1
     depleted.amount = 500
     saturated.threshold = 0.8
     saturated.amount = 3000
     fee-proportional-millionths = 2000
   }
 }
```

In this example, channels with remote node `02abcdef...` will use the custom thresholds and multipliers, while all other nodes will use the global config.

You can also specify **either** a blacklist **or** a whitelist of channel `short_id`s to control which channels are affected by the plugin:
- If `whitelist` is non-empty, only the channels in this list will be affected.
- If `blacklist` is non-empty, only the channels NOT in this list will be affected.
- You cannot use both at the same time.

#### Example configuration (`eclair.conf`):

```hocon
# Depleted state: use a multiplier
 eclair.dynamicfees.depleted.threshold = 0.3
 eclair.dynamicfees.depleted.multiplier = 0.6

# Saturated state: use a fixed amount (mutually exclusive with multiplier)
 eclair.dynamicfees.saturated.threshold = 0.8
 eclair.dynamicfees.saturated.amount = 2000

# Optionally, you can use multiplier for both:
# eclair.dynamicfees.saturated.multiplier = 3

# Set the base value for proportional fee calculation (optional, overrides eclair default)
 eclair.dynamicfees.fee-proportional-millionths = 1500

# Per-node overrides
 eclair.dynamicfees.per-node {
   "02abcdef...": {
     depleted.threshold = 0.2
     depleted.multiplier = 0.9
     saturated.threshold = 0.7
     saturated.multiplier = 1.5
   }
 }

# Whitelist: only these channels will be managed by the plugin
 eclair.dynamicfees.whitelist = ["0x1x2", "0x3x4"]

# OR, use a blacklist instead (do not use both):
# eclair.dynamicfees.blacklist = ["0x5x6"]
```

**Notes:**
- `threshold` values must be between 0 and 1, and `depleted.threshold` must be less than `saturated.threshold`.
- For each state (depleted/saturated), you must specify either `multiplier` or `amount`, but not both.
- If neither or both are set for a state, the plugin will fail to start with a configuration error.
- The `whitelist` and `blacklist` options are mutually exclusive.
- `fee-proportional-millionths` (if set) overrides the default relay fee base for proportional fee calculation in this plugin only.

This flexible configuration allows you to tailor fee adjustments per channel, per state, and per remote node, using either proportional or fixed fee strategies.

Here are the possible config parameters:

| Parameter                                 | Example Value | Description                                                                                 |
|-------------------------------------------|---------------|---------------------------------------------------------------------------------------------|
| eclair.dynamicfees.depleted.threshold     | 0.3           | 0.3 = 30% this value must be below 1 and below the saturated threshold                      |
| eclair.dynamicfees.saturated.threshold    | 0.8           | 0.8 = 80% this value must be below 1 and above the depleted threshold                       |
| eclair.dynamicfees.depleted.multiplier    | 0.6           | when in depleted state the fees will be `fee-proportional-millionths * 0.6`                 |
| eclair.dynamicfees.saturated.multiplier   | 3             | when in saturated state the fees will be `fee-proportional-millionths * 3`                  |
| eclair.dynamicfees.depleted.amount        | 100           | when in depleted state the fees will be `amount` (mutually exclusive with `multiplier`)     |
| eclair.dynamicfees.saturated.amount       | 2000          | when in saturated state the fees will be `amount` (mutually exclusive with `multiplier`)    |
| eclair.dynamicfees.fee-proportional-millionths | 1500      | (optional) base value for proportional fee calculation in this plugin; overrides eclair default |
| eclair.dynamicfees.per-node               | {...}         | (optional) map of node public keys to config blocks for per-node overrides                  |
| eclair.dynamicfees.whitelist              | ["0x1x2"]     | if non empty only the channels in this list will be affected by the plugin                  |
| eclair.dynamicfees.blacklist              | ["0x1x2"]     | if non empty only the channels NOT in this list will be affected by the plugin              |
