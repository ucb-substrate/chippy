package chipyard.config

import org.chipsalliance.cde.config.{Config}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.resources.{DTSTimebase}

// Replaces the L2 with a broadcast manager for maintaining coherence
class WithBroadcastManager
    extends Config((site, here, up) => { case SubsystemBankedCoherenceKey =>
      up(SubsystemBankedCoherenceKey, site)
        .copy(coherenceManager = CoherenceManagerWrapper.broadcastManager)
    })

class WithBroadcastParams(params: BroadcastParams)
    extends Config((site, here, up) => { case BroadcastKey =>
      params
    })

class WithSystemBusWidth(bitWidth: Int)
    extends Config((site, here, up) => { case SystemBusKey =>
      up(SystemBusKey, site).copy(beatBytes = bitWidth / 8)
    })

/** Use asynchronous reset for Rocket Chip's Debug Module. */
class WithAsyncResetRocketSubsystem
    extends Config((_, _, _) => { case SubsystemResetSchemeKey =>
      ResetAsynchronous
    })
