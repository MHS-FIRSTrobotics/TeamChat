package org.teamclutch;

import com.google.common.eventbus.Subscribe;
import org.jetbrains.annotations.NotNull;
import org.teamclutch.chat.Chat;
import org.teamclutch.chat.DataMessage;

import java.util.Scanner;

public class Main {
    private Chat chat;

    public static void main(String[] args) {
        Main main = new Main();
        main.main();
    }

    private void main() {
        final Scanner input = new Scanner(System.in);
        System.out.print("HOST: ");
        final String host = input.nextLine();
        System.out.print("username: ");
        chat = new Chat(host, input.nextLine(), null, this);
        chat.start();

        while (!Thread.currentThread().isInterrupted() && chat.isRunning()) {
            try {
                resetUserPrompt(chat);
                String s = input.nextLine();
                if (s == null) {
                    continue;
                }
                if (s.startsWith("\\show ")) {
                    s = s.toLowerCase().replaceFirst("\\\\show ", "");
                    if (s.equals("errors")) {
                        System.err.println("This is a test message!");
                    } else if (s.equals("server")) {
                        System.err.println("Server is" + (chat.isServerUp() ? " " : " not ") + "running!");
                    }
                } else {
                    chat.newMessage(s);
                }
            } catch (IllegalStateException ex) {
                if (!chat.isRunning()) {
                    break;
                } else {
                    throw ex;
                }
            }
        }
        System.out.println("Goodbye!");
    }

    private void resetUserPrompt(Chat chat) {
        System.out.print(chat.config().username() + "> ");
    }

    private static void moveAndEraseLine(int numberOfLines) {
        System.out.print(String.format("\033[%dA", numberOfLines)); // Move up
        System.out.print("\033[2K"); // Erase line content
    }

    @Subscribe
    public void newMessage(DataMessage message) {
        @NotNull String username = message.username();
        if (username.equals(chat.config().username())) {
            username = "me";
        }
        moveAndEraseLine(1);
        System.out.println("\r" + username + ": " + message.message());
        resetUserPrompt(chat);
    }
}
