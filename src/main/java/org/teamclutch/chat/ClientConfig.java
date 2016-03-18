package org.teamclutch.chat;

import com.google.common.hash.Hashing;
import io.netty.channel.Channel;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;


public final class ClientConfig {
    private HashMap<Integer, ClientConfig> instances = new HashMap<>();
    private String username;
    // don't do this in production code, this is a demo application, not for real-world use
    private int passwordHash;
    private UUID serverClientId;
    private final static SecureRandom RNG = new SecureRandom();
    private final static long seed = RNG.nextLong();
    private long messageId = 0;
    private boolean connected;

    private int portNumber;

    void configureClient(@NotNull String username, @NotNull String password, int port) {
        checkArgument(!isNullOrEmpty(username) && Character.isAlphabetic(username.charAt(0)), "Invalid username");
        checkArgument(!isNullOrEmpty(password) && password.length() > 7, "Password must be longer than 7 characters");
        if (instances.containsKey(port)) {
            throw new IllegalStateException("Port is already known to be in use!");
        } else {
            portNumber = port;
            instances.put(portNumber, this);
        }

        this.username = username;
        // don't do this in production code, this is a demo application, not for real-world use
        passwordHash = hashPassword(password);
        serverClientId = UUID.randomUUID();
    }

    private static int hashPassword(String password) {
        return Hashing.sha512().hashString(password, Charset.forName("UTF-8")).asInt();
    }

    public boolean verifyPassword(String password) {
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            return false;
        }

        return passwordHash == hashPassword(password);
    }

    @Contract(pure = true)
    public String username() {
        checkState(username != null, "Invalid Configuration");
        return username;
    }

    @Contract(pure = true)
    UUID serverClientId() {
        checkState(serverClientId != null, "Invalid Configuration");
        return serverClientId;
    }

    long nextMessageId() {
        return ++messageId;
    }

    @Contract(pure = true)
    public int port() {
        return portNumber;
    }
}
