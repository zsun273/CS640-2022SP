import java.io.*;

public class TCPend{

    public static final int DEFAULT_PORT = 8888;

    public static void main(String[] args){

        // declare parameters needed for the command line
        int senderPort = DEFAULT_PORT, receiverPort = DEFAULT_PORT;
        String filename = "", remoteIP = "";
        int mtu = 0;          // maximum transmission unit in bytes
        int sws = 0;          // sliding window size in number of segments

        // parse arguments from command line
        if (args.length == 12){
            for (int i = 0; i < args.length; i++){
                if (args[i].equals("-p")) {
                    senderPort = Integer.parseInt(args[++i]);
                } else if (args[i].equals("-s")){
                    remoteIP = args[++i];
                } else if (args[i].equals("-a")){
                    receiverPort = Integer.parseInt(args[++i]);
                } else if (args[i].equals("-f")){
                    filename = args[++i];
                } else if (args[i].equals("-m")){
                    mtu = Integer.parseInt(args[++i]);
                } else if (args[i].equals("-c")){
                    sws = Integer.parseInt(args[++i]);
                } else {
                    usage();
                    return;
                }
            }
            Sender sender = new Sender(senderPort, remoteIP, receiverPort, filename, mtu, sws);
            System.out.println("Sender created! Senderport: " + senderPort + " remoteIP: " + remoteIP
            + " receiverPort: " + receiverPort + " filename: " + filename + " mtu: " + mtu + " sws: " + sws);
        }
        else if (args.length == 8){
            for (int j = 0; j < args.length; j++){
                if (args[j].equals("-p")) {
                    receiverPort = Integer.parseInt(args[++j]);
                } else if (args[j].equals("-m")){
                    mtu = Integer.parseInt(args[++j]);
                } else if (args[j].equals("-c")){
                    sws = Integer.parseInt(args[++j]);
                } else if (args[j].equals("-f")) {
                    filename = args[++j];
                }
                else {
                    usage();
                    return;
                }
            }
            Receiver receiver = new Receiver(receiverPort, mtu, sws, filename);
            System.out.println("Receiver created! " + receiverPort + " " + mtu + " " + sws + " " + filename);
        }
        else{
            System.out.println("Error: missing arguments or having additional arguments");
            usage();
            System.exit(1);
        }
    }

    static void usage(){
        System.out.println("--------------TCPend usage---------------");
        System.out.println("Client/Sender Initialization");
        System.out.println("java TCPend [-p port] [-s remote IP] [-a remote port] [-f filename] [-m mtu] [-c sws]");

        System.out.println("Remote Receiver Set Up");
        System.out.println("java TCPend [-p port] [-m mtu] [-c sws] [-f filename]");
    }
}