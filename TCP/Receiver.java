public class Receiver {
    private int receiverPort;
    private int mtu;
    private int sws;
    private String filename;

    public Receiver(int receiverPort, int mtu, int sws, String filename) {
        this.receiverPort = receiverPort;
        this.mtu = mtu;
        this.sws = sws;
        this.filename = filename;

        System.out.println("Receiver WIP");

        while(true){
            // listening
        }
    }
}
