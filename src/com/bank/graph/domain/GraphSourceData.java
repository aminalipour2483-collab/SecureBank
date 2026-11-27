package com.bank.graph.domain;

import java.util.List;

/**
 * Everything the repository supplies to the {@link CustomerGraphBuilder}, read from one
 * consistent snapshot. The {@code *Truncated} flags say the repository stopped at its row limit.
 */
public record GraphSourceData(List<TransferFlow> flows,
                              boolean flowsTruncated,
                              List<SharedPhoneMember> phoneMembers,
                              boolean phoneMembersTruncated) {

    public GraphSourceData {
        flows = List.copyOf(flows);
        phoneMembers = List.copyOf(phoneMembers);
    }

    public static GraphSourceData empty() {
        return new GraphSourceData(List.of(), false, List.of(), false);
    }
}
