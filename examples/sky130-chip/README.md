# SKY130 Chip Example

This example demonstrates how to create top-level RTL for a chip in SKY130.
The chip's digital contents are modeled after the real chip Kodiak, which was taped out in Intel 16nm in May 2025.

The chip is divided into two packages:
- `digital-chip`: The standalone, process-agnostic digital top that can be open-sourced and developed locally.
- `sky130-chip`: The chip top which includes process-specific collateral such as IO cells.
    This package is separate since it may not be open-sourcable for other process nodes.

This top-level package may include `digital-chip` as a submodule so that `digital-chip` can be open sourced
even if the top-level package cannot be.
