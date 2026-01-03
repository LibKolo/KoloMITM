package io.github.mucute.qwq.kolomitm.data.auth;

import java.util.List;

import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.common.util.Preconditions;

public class DualPayload implements AuthPayload {
    private final List<String> chain;
    private final String token;
    private final AuthType type;

    public DualPayload(List<String> chain, String token, AuthType type) {
        Preconditions.checkArgument(type != AuthType.UNKNOWN, "DualPayload cannot be of type UNKNOWN");
        this.chain = chain;
        this.token = token;
        this.type = type;
    }

    public AuthType getAuthType() {
        return this.type;
    }

    public List<String> getChain() {
        return this.chain;
    }

    public String getToken() {
        return this.token;
    }
}
