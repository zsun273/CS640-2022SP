public class Sender {
    private int senderPort;
    private String remoteIP;
    private int receiverPort;
    private String filename;
    private int mtu;
    private int sws;

    public Sender(int senderPort, String remoteIP, int receiverPort, String filename, int mtu, int sws) {
        this.senderPort = senderPort;
        this.remoteIP = remoteIP;
        this.receiverPort = receiverPort;
        this.filename = filename;
        this.mtu = mtu;
        this.sws = sws;

        System.out.println("Sender WIP");
    }
}
