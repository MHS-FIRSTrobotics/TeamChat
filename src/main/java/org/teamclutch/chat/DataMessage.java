package org.teamclutch.chat;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.teamclutch.chat.protobuf.Message;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public class DataMessage {
    private static final Gson gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    private Message.Data cachedMessage;
    private final String username;
    private final String serverIdentifier;
    private final String messageId;
    private final String message;

    DataMessage(String username, String serverIdentifier, String messageId, String message) {
        this.username = username;
        this.serverIdentifier = serverIdentifier;
        this.messageId = messageId;
        this.message = message;

    }

    DataMessage(Message.Data data) {
        checkNotNull(data.getUsername());
        checkNotNull(data.getServerClientId());
        checkNotNull(data.getData());
        checkNotNull(data.getId());

        this.username = data.getUsername();
        this.serverIdentifier = data.getServerClientId();
        this.messageId = data.getId();
        this.message = data.getData();
    }

    @NotNull
    public String username() {
        return username;
    }

    @NotNull
    public String serverIdentifier() {
        return serverIdentifier;
    }

    @NotNull
    public String messageId() {
        return messageId;
    }

    @NotNull
    public String message() {
        return message;
    }

    @NotNull
    Message.Data createMessage() {
        if (cachedMessage == null) {
            cachedMessage = Message.Data.newBuilder().setId(messageId)
                    .setServerClientId(serverIdentifier)
                    .setUsername(username)
                    .setData(message)
                    .build();
        }

        return cachedMessage;
    }

    @NotNull
    static Message.Packet createMessage(@NotNull ClientConfig instance, @NotNull String message) {
        checkNotNull(message, "The message param is null");
        Message.Data.Builder builder = Message.Data.newBuilder().setId(Long.toString(instance.nextMessageId()))
                .setServerClientId(instance.serverClientId().toString())
                .setUsername(instance.username())
                .setData(message);
        return newPacketBuilder().setData(builder).build();
    }

    @NotNull
    private static Message.Packet.Builder newPacketBuilder() {
        return Message.Packet.newBuilder();
    }

    @NotNull
    static Message.Packet createPing() {
        Message.Ping.Builder builder = Message.Ping.newBuilder().setTimeSent(System.currentTimeMillis());
        return newPacketBuilder().setPing(builder).build();
    }

    @NotNull
    static Message.Packet createNewUserRequest(@NotNull ClientConfig instance) {
        Message.NewUser build = Message.NewUser.newBuilder()
                .setUsername(instance.username())
                .setId(instance.serverClientId().toString()).build();
        return newPacketBuilder().setNew(build).build();
    }

    @NotNull
    static Message.Packet createMessageRequest(String serverIdentifier, long messageId, long messageIdEnd) {
        final Message.DataRequest.Builder builder = Message.DataRequest.newBuilder();
        final Message.DataRequest request;
        if (messageIdEnd == 0) {
            request = builder.setId(serverIdentifier + ":" + messageId).build();
        } else if (messageId < messageIdEnd) {
            request = builder.setId(serverIdentifier + ":" + messageId + "-" + messageIdEnd).build();
        } else {
            throw new IllegalArgumentException("The end message id must 0 or greater than the first message id");
        }

        return newPacketBuilder().setRequest(request).build();
    }

    @NotNull
    static Message.Packet createDataPackage(@NotNull DataMessage... messages) {
        List<Message.Data> dataList = new ArrayList<>(messages.length);
        for (DataMessage message : messages) dataList.add(message.createMessage());
        Message.DataPackage build = Message.DataPackage.newBuilder().addAllMessages(dataList).build();
        return newPacketBuilder().setPkg(build).build();
    }

    @NotNull
    static String encode(Message.Packet packet) {
        String s = gson.toJson(new GsonEncodable(packet));
        // Replace the line returns in such a way to only be present at the end
        s = s.replace("\r\n", "").replace("\n", "") + '\n';
        return s;
    }

    @Nullable
    static Message.Packet decode(String packet) {
        GsonEncodable encodable = gson.fromJson(packet, GsonEncodable.class);
        if (encodable.s != encodable.d.length()) {
            if (Chat.DEBUG_VERSION) {
                throw new IllegalArgumentException("Type 1 Corrupted Data!");
            }
        }

        try {
            return Message.Packet.parseFrom(ByteString.copyFromUtf8(encodable.d));
        } catch (InvalidProtocolBufferException e) {
            if (Chat.DEBUG_VERSION) {
                throw new IllegalArgumentException("Type 2 Corrupted Data! Found: " + encodable.d, e);
            }
        }

        return null;
    }

    private static class GsonEncodable {
        private int s;
        private String d;


        /**
         * Allows to be deserialized by Gson, don't remove
         *
         * @see Gson#fromJson(String, Class)
         */
        private GsonEncodable() {

        }

        private GsonEncodable(Message.Packet data) {
            d = data.toByteString().toStringUtf8();
            s = d.length();
        }
    }
}
