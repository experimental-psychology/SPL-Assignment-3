package bgu.spl.net.impl.stomp;

import bgu.spl.net.srv.Server;

public class StompServer {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: StompServer <port> <tpc/reactor>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        String mode = args[1];

        switch (mode) {
            case "tpc":
                Server.<String>threadPerClient(
                        port,
                        () -> new StompMessagingProtocolImpl(), 
                        () -> new StompMessageEncoderDecoder()
                ).serve();
                break;
            case "reactor":
                Server.<String>reactor(
                        Runtime.getRuntime().availableProcessors(),
                        port,
                        () -> new StompMessagingProtocolImpl(),
                        () -> new StompMessageEncoderDecoder()
                ).serve();
                break;
            default:
                System.out.println("Unknown mode. Use 'tpc' or 'reactor'.");
        }
    }
}