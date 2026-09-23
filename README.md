# Matter-to-AdHoc — Matter cluster XML → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Converts the machine-readable **Matter data model** (Connectivity Standards Alliance) cluster definitions into
[AdHoc](https://github.com/AdHoc-Protocol) protocol-description `.cs` files: one project per cluster with its
attributes, commands (fire-and-forget or request/response), events, enums, bitmaps and structs, plus a
`Client ↔ Server` connection that wires them as AdHoc branches and RPC calls.

Self-contained: the converter, a local copy of the emitter helpers (`src/org/unirail/adhoc`), sample fetcher,
build and validation scripts all live in this folder.

## Before and after

`samples/WindowCovering.xml`, 584 lines — [source](samples/WindowCovering.xml) → [result](AdHoc/WindowCovering.cs).
A command whose only argument is a `percent100ths` becomes a pack whose field is bit-packed into its hard range.

```xml
  <commands>
    <command id="0x00" name="UpOrOpen" direction="commandToServer" response="Y">
      <access invokePrivilege="operate"/>
      <mandatoryConform/>
    </command>
    <!-- … DownOrClose, StopMotion, GoToLiftValue … -->
    <command id="0x05" name="GoToLiftPercentage" direction="commandToServer" response="Y">
      <access invokePrivilege="operate"/>
      <otherwiseConform>
        <mandatoryConform>
          <andTerm>
            <feature name="LF"/>
            <feature name="PA_LF"/>
          </andTerm>
        </mandatoryConform>
        <!-- … optionalConform on feature LF … -->
      </otherwiseConform>
      <field id="0" name="LiftPercent100thsValue" type="percent100ths">
        <mandatoryConform/>
        <constraint>
          <desc/>
        </constraint>
      </field>
    </command>
  </commands>
```

```csharp
        /**
        Matter command (acknowledged with a status only) UpOrOpen (id 0x00)
        */
        class UpOrOpen {
            public const uint command_id = 0x0;
            public const string access = "invoke:operate";
            public const string conformance = "M";
        }

        // … DownOrClose, StopMotion, GoToLiftValue …

        /**
        Matter command (acknowledged with a status only) GoToLiftPercentage (id 0x05)
        */
        class GoToLiftPercentage {
            public const uint command_id = 0x5;
            public const string access = "invoke:operate";
            public const string conformance = "otherwise(M[(LF&PA_LF)]; O[LF])";
            [MatterType("percent100ths"), MinMax(0, 10000), FieldId(0x0), Constraint("desc")] ushort LiftPercent100thsValue;
        }

        interface Interaction : Connects<Client, Server> {
            // commands acknowledged with a status only: fire-and-forget from the client
            [l____________<(UpOrOpen, DownOrClose, StopMotion, GoToLiftValue, GoToLiftPercentage, GoToTiltValue, GoToTiltPercentage)>]
            struct Invoke { }
```

## Links

| What | Where |
|:--|:--|
| Cluster XML files (source of the samples) | https://github.com/project-chip/connectedhomeip/tree/master/data_model/1.7/clusters |
| Global types (enums, bitmaps, structs, typedefs shared by clusters) | https://github.com/project-chip/connectedhomeip/tree/master/data_model/1.7/globals |
| All published data-model versions (1.0 … 1.7) | https://github.com/project-chip/connectedhomeip/tree/master/data_model |
| How the data-model XML is produced from the spec | https://github.com/project-chip/connectedhomeip/blob/master/data_model/README.md |
| ZAP-format equivalent of the same clusters (used by the Matter SDK code generator) | https://github.com/project-chip/connectedhomeip/tree/master/src/app/zap-templates/zcl/data-model/chip |
| Matter specification download (CSA, requires accepting the license form in a browser) | https://csa-iot.org/developer-resource/specifications-download-request/ |
| AdHoc protocol description format | https://github.com/AdHoc-Protocol |

## Layout

```
Matter_to_AdHoc/
  README.md
  src/org/unirail/Matter2AdHoc.java          the converter (Java 17, javax.xml only)
  src/org/unirail/adhoc/AdHocWriter.java     local emitter helpers (naming, docs, dashboard, hosts, attributes)
  src/org/unirail/adhoc/Json.java            (unused here, part of the standard helper set)
  fetch-samples.sh                           downloads 12 clusters + 5 global files (Matter 1.7) into samples/
  build.sh                                   javac + run over samples/ into AdHoc/
  validate.sh                                AdHocAgent parse-only validation of AdHoc/*.cs
  samples/                                   the fetched XML (clusters/*.xml at top level, globals/*.xml)
  AdHoc/                                     generated descriptors (+ *.branches.txt dumps from validation)
```

## Commands

```bash
./fetch-samples.sh                 # VERSION=1.4 ./fetch-samples.sh for another data-model version
./build.sh                         # → AdHoc/<Cluster>.cs
./validate.sh AdHoc                # every file must print OK

# manual run: <cluster.xml | folder> [output folder] [--globals <dir>]
java -cp out org.unirail.Matter2AdHoc samples/OnOff.xml out_dir --globals samples/globals
```

Global types default to `<input folder>/globals`; only the global enums/bitmaps/structs a cluster actually
references (transitively through structs and list entries) are copied into that cluster's file.

## What is generated per cluster

```csharp
namespace org.matter {
    /** Packs Inventory: commands, responses, events, Attributes, structs (ids assigned by AdHocAgent) */
    public interface OnOff {
        public struct ClusterInfo { cluster_id, cluster_name, revision }   // constants container
        [Flags] enum Features { Lighting = 1, DeadFrontBehavior = 2, ... } // FeatureMap bits
        enum StartUpOnOffEnum { Off = 0, On = 1, Toggle = 2 }             // <enum>
        [Flags] enum SomeBitmap { ... }                                    // <bitmap>
        class SomeStruct { ... }                                           // <struct>, sub-pack
        class Attributes { [AttrId(0x0), Access("read:view"), Quality("nonVolatile scene")] bool OnOff; ... }
        class Off { public const uint command_id = 0x0; ... }              // commandToServer, status-only reply
        class GetUserRequest { ... }  class GetUserResponse { ... }        // command with a data response
        class DoorLockAlarm { public const uint event_id = 0x0; ... }      // <event>
        struct Client : Host { }  struct Server : Host { }
        interface Interaction : Connects<Client, Server> {
            [l____________<(Off, On, Toggle, ...)>] struct Invoke { }      // fire-and-forget commands
            (L____________, GetUserResponse) GetUser(GetUserRequest req);  // RPC: client calls, server answers
            [____________r<(DoorLockAlarm, ...)>] struct Report { }        // events, server → client
            [_____lr_____<Attributes>] struct AttributeAccess { }          // reads / writes / reports
        }
        // custom attribute declarations: FieldId, AttrId, Access, Quality, Default, MatterType, Constraint, Conformance
    }
}
```

## Mapping

| Matter data model | AdHoc |
|:--|:--|
| `<cluster id name revision>` + `<revisionHistory>` | `struct ClusterInfo` constants container; history in its doc comment |
| `<features>` | `[Flags] enum Features` (value `1 << bit`, summary as doc); one feature → constants container |
| `<enum>` with items | `enum` (values kept as written, hex included); fewer than two items → `struct` constants container, referencing fields fall back to `byte`/`ushort` + `[MatterType]` |
| `<bitmap>` with bitfields | `[Flags] enum`; multi-bit fields (`from`/`to`) become the mask; `: long`/`: ulong` above 31/63 bits; single bitfield → constants container |
| `<struct>` | `class` (sub-pack); global structs are copied in when referenced |
| `<attributes>` | one pack `Attributes`; per field `[AttrId]`, `[Access]`, `[Quality]`, `[Default]`, `[Constraint]`, `[Conformance]`; `nullable` quality or non-mandatory conformance → `T?` on value types |
| `<command direction="commandToServer" response="Y">` | pack named after the command (consts `command_id`, `access`, `conformance`), sent in the `Invoke` state |
| `<command … response="XResponse">` + `<command direction="responseFromServer">` | `XRequest` / `XResponse` packs and an RPC method `(L____________, XResponse) X(XRequest req);` |
| `<event>` | pack (consts `event_id`, `priority`, `access`, `conformance`), sent by the server in the `Report` state |
| `bool`, `uint8…uint64`, `int8…int64`, `single`, `double` | `bool`, `byte…ulong`, `sbyte…long`, `float`, `double` |
| `enum8`/`enum16`, `map8…map64` used directly | `byte`/`ushort`/… + `[MatterType("enum8")]` |
| **wall-clock timestamps** `epoch-s`, `epoch-us`, `posix-ms`, `utc` | **`DateTime`** — AdHoc models a point in time natively; the Matter epoch is a wire detail AdHoc replaces. The source type stays as a trailing comment |
| **elapsed / monotonic times** `elapsed-s`, `systime-ms`, `systime-us` | one shared **`class ElapsedSeconds : Duration { max; precision; }`** alias per unit (also `SystemTimeMilliseconds`, `SystemTimeMicroseconds`), emitted only when a field of that type exists |
| identity and tag types AdHoc has no concept for (`node-id`, `cluster-id`, `fabric-idx`, `endpoint-no`, `vendor-id`, `status`, `temperature`, …) | base integer of the spec + `[MatterType("node-id")]` |
| `percent`, `percent100ths` | `[MinMax(0, 100)]` / `[MinMax(0, 10_000)]` — the range is part of the type, so it is bit-packed rather than tagged |
| `string` (+ `maxLength`) | `string`, `[D(+N)]` whenever the spec states N |
| `octstr` (+ `maxLength` / `allowed N`) | `[D(N)] Binary[,,]` |
| `list` + `<entry type>` (+ `maxCount`) | `T[,,]` with `[D(count)]`; `list<octstr>` items become a small sub-pack `octstr_max_N { Binary[,,] bytes; }` because a TYPEDEF of a byte list cannot be a list item |
| any constraint with literal bounds — `<between from to>`, `<min>`, `<max>` — on an integer field | `[MinMax(a, b)]`, filling the open end from the declared type (Matter constraints are hard limits, so the field is bit-packed) plus the textual `[Constraint]` |
| a bound that references another attribute or a `compute` expression | `[Constraint("…")]` text plus an inline `// bounds depend on another attribute, cannot be bit-packed` |
| no explicit collection bound | project-wide `enum _DefaultMaxLengthOf { Arrays/Maps/Sets/Strings = 65_535 }`, instead of AdHoc's 255 default that would truncate Matter payloads |
| conformance (`mandatoryConform`, `optionalConform`, `provisionalConform`, `otherwiseConform`, feature/condition expressions) | `[Conformance("M[LT]")]`, `"O[!OFFONLY]"`, `"P"`, `"otherwise(M[LT]; O)"` … |
| `<access>` | `[Access("read:view write:manage")]` / `invoke:operate timed` on commands |
| `<quality>` | `[Quality("nullable nonVolatile scene quieterReporting atomicWrite")]` |

Pack ids in the Dashboard are left to AdHocAgent: Matter command/event ids are only unique inside a cluster and
requests/responses may share them, so the Matter ids are kept as `const` members instead.

## Varint: what a cluster XML does and does not say

How Matter stores a value decides nothing here — AdHoc lays out its own frame, so a `uint16` on the Matter wire
is not a reason to decline `[A]`, `[V]` or `[X]`. What decides those attributes is where a field's values
actually sit, and a cluster XML states ranges and semantic types but never a distribution. So the converter emits
no varint attribute on its own; it acts on the hints the data model *does* give.

The arithmetic, once: varint wins while the typical distance from the base stays under roughly two million, and
past 268 435 455 it always loses, costing a fifth byte on every packet forever.

| What the cluster XML states | What the converter does |
|:--|:--|
| `percent` (0…100), `percent100ths` (0…10 000), a literal `between` / `min` / `max` constraint | a hard range, so it is bit-packed with `[MinMax]` — 115 fields; no varint question arises |
| `epoch-us`, `posix-ms` — a microsecond or millisecond epoch | monotonic and systematically past 268 435 455, exactly where varint loses; emitted as `DateTime`, which encodes the instant directly. The field carries `// physics: monotonic and huge, varint would always lose here` |
| `systime-ms`, `systime-us` — time since boot | same shape, emitted as a `Duration` alias with the same comment |
| `elapsed-s` — a timeout or an elapsed span | clusters near zero, an `[A]` shape; emitted as a `Duration` alias, which already sizes it to the smallest container, and the field says so |
| an attribute named `NumberOf*`, `*Count`, `*Index`, `*Sequence` | a counter or index: floor at 0, unbounded above. 30 fields carry `// physics: counter/index, floor at 0, unbounded above -> consider [A]` |
| an attribute named `Remaining*` | a remaining budget hugs its ceiling: `// physics: … -> consider [V(max)]` |
| a name containing `Delta`, `Offset`, `Deviation`, `Correction`, `Drift` | centred on zero: `// physics: a delta centred on zero -> consider [X(amplitude)]` |

The hints are comments on the field, never attributes: picking a varint base is a decision about the data that
the person refining the description makes, and the comment puts the question where that decision belongs. They
are emitted only for integers wider than one byte that have no hard range — a `[MinMax]` field is already packed,
and a `byte` has nothing for varint to drop.

## Dropped or approximated

Everything the converter could not express is marked with an inline comment at the place it was dropped, so the
file itself shows what needs a human decision (`// bounds depend on another attribute, cannot be bit-packed`,
`// item count is bounded by another attribute; falls back to _DefaultMaxLengthOf.Arrays`,
`// values: constants container X`, `// nested list not supported`, and the `// physics: …` hints above).

- Conformance and constraint expressions are carried as text metadata, not enforced (feature-dependent
  mandatory/optional rules become `T?`).
- Bounds stated as a reference to another attribute (`between 1..attr:NumberOfTotalUsersSupported`) or as a
  `compute` expression cannot become `[MinMax]`, because AdHoc needs compile-time constants. 47 fields across the
  12 samples are in this state; each carries the comment above so the range can be pinned by hand.
- Global commands (`globals/Commands.xml`: AtomicRequest/AtomicResponse) are not attached to clusters.
- `device_types` and `namespaces` folders of the data model are out of scope.
- A response declared in the cluster but not referenced by any command is emitted as a server-sent pack in
  `Report`; a command whose named response is missing from the file is treated as fire-and-forget (both are
  printed as notes by the converter).
- Nested `list<list<…>>` (not present in the shipped samples) falls back to `int[,,]` with a comment.

## Validation result (Matter 1.7 samples, AdHocAgent parse-only)

| Cluster | Result | Cluster | Result |
|:--|:--|:--|:--|
| BasicInformation | OK | LevelControl | OK |
| ColorControl | OK | OnOff | OK |
| Descriptor | OK | Switch | OK |
| DoorLock | OK (6 RPC calls, 15 fire-and-forget commands, 5 events) | TemperatureMeasurement | OK |
| FanControl | OK | Thermostat | OK |
| Identify | OK | WindowCovering | OK |

Totals over the 12 samples: 134 packs, 85 enums, 8 RPC methods, 24 events, **115 `[MinMax]` bit-packed fields**
and 13 `DateTime` fields. The agent's `INF … appears to be a flags enum` hints refer to plain enums whose values
happen to be powers of two and are expected.

Only `epoch-s` occurs among the temporal types in these 12 clusters, so no `Duration` alias is emitted for this
sample set; clusters that use `elapsed-s` or `systime-*` (for example `OperationalState`, `TimeSync`) will get
the shared alias.
