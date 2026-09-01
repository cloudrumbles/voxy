package me.cortex.voxy.common.distantgen;

import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;

public final class VoxyDistantGenTickets {
    public static final TicketType<ChunkPos> TICKET = TicketType.create(
            "voxy_distant_gen", Comparator.comparingLong(ChunkPos::toLong));

    // Level 33 = BORDER in 1.20.1: chunk reaches FULL ChunkStatus, no block ticks, no entity ticks.
    // Matches DH's choice (InternalServerGenerator.requestChunkFromServerAsync).
    public static final int TICKET_LEVEL = 33;

    private VoxyDistantGenTickets() {}
}
