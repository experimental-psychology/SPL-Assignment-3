package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;
import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Server;

import java.util.Locale;
import java.util.function.Supplier;

public class StompServer {

    public static void main(String[] args) {
        if (args == null || args.length != 2) {
            printUsageAndExit();
            return;
        }

        final int port;
        try {
            port = Integer.parseInt(args[0]);
            if (port <= 0 || port > 65535) {
                printUsageAndExit();
                return;
            }
        } catch (NumberFormatException e) {
            printUsageAndExit();
            return;
        }

        final String mode = args[1].toLowerCase(Locale.ROOT);

        Supplier<StompMessagingProtocol<String>> protocolFactory = StompMessagingProtocolImpl::new;
        Supplier<MessageEncoderDecoder<String>> encdecFactory = StompMessageEncoderDecoder::new;

        Server<String> server;
        if ("tpc".equals(mode)) {
            server = Server.threadPerClient(port, protocolFactory, encdecFactory);
        } else if ("reactor".equals(mode)) {
            int nthreads = Runtime.getRuntime().availableProcessors();
            server = Server.reactor(nthreads, port, protocolFactory, encdecFactory);
        } else {
            printUsageAndExit();
            return;
        }

        server.serve();
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: <port> <tpc|reactor>");
    }
}
