import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.*;

/*
    Sender class

    When send packets:
    1): if exceeds max transfer time (16), report error and stop
    2): set a timer for the packet, if not receiving ack in a timeout, retransmit
    3): put sent data to buffer (sliding window)

    When receive packets:
    1): check ack, remove acked data and any data before the acked data if in buffer
                   if 3 same consecutive acks, resend packets in buffer
    2): update rtt (timeout) according to acked packet time
 */
public class Sender {
    private DatagramSocket senderSocket;
    private int senderPort;
    private String remoteIP;
    private int receiverPort;
    private String filename;
    private int mtu;
    private int sws;
    private int dupAckTimes; // keep track of same consecutive ack times

    /** stores the file being sent as a byte array */
    private FileInputStream fileReader;

    /** file being sent */
    private File file;

//    /** current sequence number*/
//    private int sequenceNum;
//
//    /** next expected sequence number after sending data*/
//    private int nextSeqNum;

    /** current ack number*/
    private int currAck;

    /** Last Acked sequence position*/
    private int lastAcked;

    /** Last byte being sent, calculated by sequence number + length of data ,
     * next packet's sequence number should be this + 1*/
    private int lastSent;

    private Timer timer;
    private long startTime;

    private static final int SYN = 2;
    private static final int FIN = 1;
    private static final int ACK = 0;
    private static final int NUM_RETRANSMISSION = 16;

    private HashMap<Integer, byte[]> slidingWindow; // packets in sliding window are sent but unacked packets
    private long timeout;  // timeout, update after each packet received
    private HashMap<Integer, Timer> timerMap; // map stores packet -> a timer corresponds to it
    private HashMap<Integer, Integer> timesMap; // map stores packet -> send times
    private int payloadsize;
    boolean open;
    boolean stopSend;
    boolean finalPacket;

    private int dataTransfered;
    private int numPacketsSent;
    private int getNumRetransmission;
    private int numDupAcks;

    public Sender(int senderPort, String remoteIP, int receiverPort, String filename, int mtu, int sws) {
        this.senderPort = senderPort;
        this.remoteIP = remoteIP;
        this.receiverPort = receiverPort;
        this.filename = filename;
        this.mtu = mtu;
        this.sws = sws;
        this.dupAckTimes = 0;
        try {
            this.file = new File(filename);
            this.fileReader = new FileInputStream(this.file);
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.startTime = System.nanoTime();
        this.timeout = 0;
        this.timerMap = new HashMap<>();
        this.timesMap = new HashMap<>();
        this.lastSent = -1;
//        this.sequenceNum = 0;
//        this.nextSeqNum = 0;
        this.currAck = 0;
        this.lastAcked = -1;
        this.slidingWindow = new HashMap<>(sws); // assigning a size may not work here as hashmap can resize itself?
        this.open = false;
        this.stopSend = false;
        // mtu - header: (seq number + ack + timestamp + length + flag + all zero + checksum)
        this.payloadsize = mtu - 24;

        this.dataTransfered = 0;
        this.numPacketsSent = 0;
        this.getNumRetransmission = 0;
        this.numDupAcks = 0;

        System.out.println("Sender: from port: " + senderPort + " to port: " + receiverPort);

        try {
            this.senderSocket = new DatagramSocket(senderPort);
            System.out.println("Sender socket successfully bind to port: " + senderPort);
        } catch (SocketException e) {
            e.printStackTrace();
            System.out.println("Sender Socket binding failed!");
            System.exit(1);
        }

        try {
            ReceiveThread rthread = new ReceiveThread();
            rthread.start();
            SendThread sthread = new SendThread();
            sthread.start();
            System.out.println("Sender start listening with port " + senderPort);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }

        try {
            // three-way handshake initialization
            // send first SYN here from sender
            // TODO: do we need to make sure only one thread is sending (using lock?)
            ArrayList<Integer> flagBits = new ArrayList<>();
            flagBits.add(SYN);
            byte[] data = createPacket(lastSent+1, new byte[0], flagBits);
            DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(remoteIP), receiverPort);
            senderSocket.send(packet);
            //slidingWindow.put(sequenceNum, data);
            output(data, true);
            updateAfterSend(data);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(3);
        }
    }

    private void updateAfterSend(byte[] data) {
        // update next sequence number here
        int lengthNFlags = getLengthNFlags(data);
        int length = getLength(lengthNFlags);
        int s = getFlag(lengthNFlags, SYN);
        int f = getFlag(lengthNFlags, FIN);

        if (s == 1 || f == 1 || length > 0){
            //sequenceNum = nextSeqNum;
            lastSent += length;
            if (length > 0){
                //nextSeqNum = sequenceNum + length;
                dataTransfered += length;
            } else {
                //nextSeqNum = sequenceNum + 1;
            }
        }
        numPacketsSent ++;
    }

    private void updateAfterReceive(byte[] data) {
        int lengthNFlags = getLengthNFlags(data);
        int length = getLength(lengthNFlags);
        int s = getFlag(lengthNFlags, SYN);
        int f = getFlag(lengthNFlags, FIN);
        int a = getFlag(lengthNFlags, ACK);

        int receivedSeqNum = getSequenceNum(data);
        if (s == 1 || f == 1 || length > 0){
            if (length > 0){
                currAck = receivedSeqNum + length;
            } else {
                currAck = receivedSeqNum + 1;
            }
        }

        if (a == 1){
            int ack = getAckNum(data) - 1;
            if (ack == lastAcked) { // fast retransmit
                dupAckTimes ++;
                numDupAcks ++;
                if (dupAckTimes >= 3) {
                    for (int key : slidingWindow.keySet()) {
                        byte[] packet = slidingWindow.get(key);
                        try {
                            DatagramPacket udpPacket = new DatagramPacket(packet, packet.length, InetAddress.getByName(remoteIP), receiverPort);
                            senderSocket.send(udpPacket);

                            setTimeOut(getSequenceNum(packet), packet); // put a new timer after this
                            output(packet, true);
                            getNumRetransmission ++ ;
                        } catch (Exception e){
                            e.printStackTrace();
                        }
                    }
                }
            } else { // normal
                lastAcked = getAckNum(data) - 1;
                dupAckTimes = 0;
                for (int key : slidingWindow.keySet()) { // remove acked data from buffer
                    if (key <= receivedSeqNum) {
                        slidingWindow.remove(key);
                        timerMap.get(key).cancel();
                        timerMap.remove(key);
                        timesMap.remove(key);
                    }
                }
            }

            // update timeout
            timeout = timeOutCalculation(receivedSeqNum, getTimeStamp(data));

            // refresh timer
            if (timer != null){
                timer.cancel();
            }
        }
        if (s == 1){
            open = true;
        }
        if (f == 1){
            open = false;
            stopSend = true;
        }
    }


    private void setTimeOut(int seqNum, byte[] packet) {
        int times = timesMap.getOrDefault(seqNum, 0) + 1;
        if (times > NUM_RETRANSMISSION) { // need to halt the process, but how?
            System.out.println("Maximum transmission times of a single packet is reached. Transmission failed!!!");
        }
        Timer timer = new Timer();

        // cancel previous timer on the same packet if any
        Timer preTimer = timerMap.get(seqNum);
        if (preTimer != null)
            preTimer.cancel();

        timerMap.put(seqNum, timer);
        timesMap.put(seqNum, times);
        timer.schedule(new TimeCheck(seqNum, packet), timeout);
    }

    class TimeCheck extends TimerTask {
        int seqNum;
        byte[] packet;
        TimeCheck(int seqNum, byte[] data) {
            this.seqNum = seqNum;
            this.packet = data;
        }

        public void run() { // resend package if timeout
            try {
                DatagramPacket udpPacket = new DatagramPacket(packet, packet.length, InetAddress.getByName(remoteIP), receiverPort);
                senderSocket.send(udpPacket);

                setTimeOut(getSequenceNum(packet), packet); // put a new timer after this
                output(packet, true);
                getNumRetransmission ++;
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    /** Create a packet in required format */
    private byte[] createPacket(int sequenceNum, byte[] payload, ArrayList<Integer> flags) {
        byte[] sequenceNumBytes = ByteBuffer.allocate(4).putInt(sequenceNum).array();
        byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(currAck).array();
        byte[] timestampBytes = ByteBuffer.allocate(8).putLong(System.nanoTime()).array();

        int lengthAndFlags = payload.length << 3;
        for (int flag: flags){
            int flagBits = 1 << flag;               // SYN 2 -> 100, FYN 1 -> 10, ACK 0 -> 0
            lengthAndFlags = lengthAndFlags | flagBits;
        }

        byte[] lengthFlagsBytes = ByteBuffer.allocate(4).putInt(lengthAndFlags).array();
        byte[] zeros = new byte[2];
        short checkSum = 0;
        byte[] checkSumBytes = ByteBuffer.allocate(2).putShort(checkSum).array();

        ByteBuffer packet = ByteBuffer.allocate(24 + payload.length);
        packet.put(sequenceNumBytes);
        packet.put(ackNumBytes);
        packet.put(timestampBytes);
        packet.put(lengthFlagsBytes);
        packet.put(zeros);
        packet.put(checkSumBytes);
        packet.put(payload);

        if (payload.length > 0 )
            slidingWindow.put(sequenceNum, packet.array());

        if (checkSum == 0){
            checkSum = computeCheckSum(packet.array());
            packet.putShort(22, checkSum);
        }

        return packet.array();
    }

    public byte[] slicingByteArray(byte[] arr, int start, int end) {
        if (end >= arr.length) {
            end = arr.length;
        }
        // Get the slice of the Array
        byte[] slice = new byte[end - start];
        // Copy elements of arr to slice
        for (int i = 0; i < slice.length; i++) {
            slice[i] = arr[start + i];
        }
        return slice;
    }

    /** Length of data portion (in bytes) */
    public int getLength(int n) {
        return n >> 3;
    }

    public int getFlag(int n, int k){       // -->         ->          -
        return (n >> k) & 1;                // 100 - syn, 010 - ack, 001 - fin
    }

    public int getSequenceNum(byte[] packet){
        byte[] sequenceNumBytes = slicingByteArray(packet, 0, 4);
        return ByteBuffer.wrap(sequenceNumBytes).getInt();
    }

    public int getAckNum(byte[] packet){
        byte[] ackNumBytes = slicingByteArray(packet, 4, 8);
        return ByteBuffer.wrap(ackNumBytes).getInt();
    }

    public long getTimeStamp(byte[] packet){
        byte[] timeStampBytes = slicingByteArray(packet, 8, 16);
        return ByteBuffer.wrap(timeStampBytes).getLong();
    }

    public int getLengthNFlags(byte[] packet){
        byte[] lengthNFlagsBytes = slicingByteArray(packet, 16, 20);
        return ByteBuffer.wrap(lengthNFlagsBytes).getInt();
    }

    public short getCheckSum(byte[] packet){
        byte[] checksumBytes = slicingByteArray(packet, 22, 24);
        return ByteBuffer.wrap(checksumBytes).getShort();
    }


    /** Format: [snd/rcv] [time] [flag-list] [seq-number] [number of bytes] [ack number] */
    private void output(byte[] packetBytes, boolean send) {
        String output = "";
        if (send == true){
            output += "snd";
        } else {
            output += "rcv";
        }
        output += " ";
        String time = String.valueOf((double)(System.nanoTime() - this.startTime) / Math.pow(10, 9));
        output += time.substring(0, 6);
        output += " ";
        int lengthNFlags = getLengthNFlags(packetBytes);

        if (getFlag(lengthNFlags, SYN) == 1){
            output += "S ";
        } else {
            output += "- ";
        }
        if (getFlag(lengthNFlags, ACK) == 1){
            output += "A ";
        } else {
            output += "- ";
        }
        if (getFlag(lengthNFlags, FIN) == 1){
            output += "F ";
        } else {
            output += "- ";
        }
        if (getLength(lengthNFlags) > 0){
            output += "D ";
        } else {
            output += "- ";
        }

        output += String.valueOf(getSequenceNum(packetBytes)) + " " + String.valueOf(getLength(lengthNFlags)) + " "
                + String.valueOf(getAckNum(packetBytes));

        System.out.println(output);
    }

    // not sure if correct
    private short computeCheckSum(byte[] packet) {
        ByteBuffer bb = ByteBuffer.wrap(packet);
        bb.rewind();
        int accumulation = 0;
        for (int i = 0; i < packet.length; ++i){
            accumulation += 0xffff & bb.getShort();
        }
        // pad to an even number of shorts
        if (packet.length % 2 > 0){
            accumulation += (bb.get() & 0xff) << 8;
        }
        // add potential carry over
        accumulation = ((accumulation >> 16) & 0xffff) + (accumulation & 0xffff);
        short checksum = (short) (~accumulation & 0xffff);
        return checksum;
    }

    /** Return timeout in nanoseconds */
    private long timeOutCalculation(int sequenceNum, long timeStamp) {
        long ERTT = 0, EDEV = 0, T0, SRTT = 0, SDEV;
        if (sequenceNum == 0){
            ERTT = System.nanoTime() - timeStamp;
            EDEV = 0;
            T0 = 2*ERTT;
        } else {
            SRTT = System.nanoTime() - timeStamp;
            SDEV = Math.abs(SRTT - ERTT);
            ERTT = (long) (0.875 * ERTT + (1-0.875) * SRTT);
            EDEV = (long) (0.75*EDEV + (1-0.75) * SDEV);
            T0 = ERTT + 4*EDEV;
        }
        return T0;
    }

    public class ReceiveThread extends Thread {
        public void run(){
            try {
                byte[] incomingData = new byte[24]; // receiver will not send data back
                DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);

                try {
                    while(stopSend == false){
                        senderSocket.receive(incomingPacket);

                        int lengthNFlags = getLengthNFlags(incomingData);
                        int length = getLength(lengthNFlags);
                        int s = getFlag(lengthNFlags, SYN);
                        int f = getFlag(lengthNFlags, FIN);
                        int a = getFlag(lengthNFlags, ACK);

                        // TODO: check if checksum is correct
                        short originalChecksum = getCheckSum(incomingData);
                        // reset checksum to zero
                        ByteBuffer bb = ByteBuffer.wrap(incomingData);
                        bb.putShort(22, (short)0);
                        // compute current checksum
                        short currChecksum = computeCheckSum(incomingData);
                        if (currChecksum != originalChecksum) {
                            System.out.println("Check sum failed! Data Corrupted!");
                            // drop the packet
                            continue;
                        }

                        // check complete, handle incoming packet
                        // print out received packet
                        output(incomingData, false);

                        // update variables after receiving this packet
                        updateAfterReceive(incomingData);

                        ArrayList<Integer> flagBits = new ArrayList<>();
                        if (s == 1 || f == 1) { // receive a SYN or FIN from receiver
                            flagBits.add(ACK);
                            byte[] ackPacket = createPacket(lastSent+1, new byte[0], flagBits);
                            senderSocket.send(new DatagramPacket(ackPacket, ackPacket.length, InetAddress.getByName(remoteIP), receiverPort));

                            // output the packet just sent
                            output(ackPacket, true);

                        }
                        else { // only a==1
                            // check sliding window hashmap has size 0 --> all acked
                            if (finalPacket == true && slidingWindow.keySet().size() == 0) { // we received the ack from final packet
                                try {
                                    flagBits.add(FIN);
                                    byte[] finData = createPacket(lastSent+1, new byte[0], flagBits);
                                    DatagramPacket finPacket = new DatagramPacket(finData, finData.length, InetAddress.getByName(remoteIP), receiverPort);
                                    senderSocket.send(finPacket);

                                    // output packet sent
                                    output(finData, true);

                                    // update variables after sent
                                    updateAfterSend(finData);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }

                        }

                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    public class SendThread extends Thread {
        public void run() {

                try {
                    while(stopSend == false) {

                        // wait for connection established
                        if (open == true && finalPacket == false) { // send data

                            // determine how many bytes to send OR wait
                            long remainingBytes = file.length() - 1 - lastSent;
                            int swCapacity = sws - (lastSent - lastAcked);

                            if (remainingBytes <= mtu && remainingBytes <= swCapacity) { // send in one transmission
                                finalPacket = true;  // can we set finalPacket to true at this point?
                                byte[] data = new byte[(int) remainingBytes];
                                if (fileReader.read(data) != -1) {
                                    ArrayList<Integer> flagBits = new ArrayList<>();
                                    flagBits.add(ACK);
                                    byte[] packet = createPacket(lastSent + 1, data, flagBits);

                                    DatagramPacket udpPacket = new DatagramPacket(packet, packet.length, InetAddress.getByName(remoteIP), receiverPort);
                                    senderSocket.send(udpPacket);
                                    setTimeOut(lastSent + 1, packet);
                                    output(packet, true);
                                    updateAfterSend(data);
                                }
                            }

                            else if (remainingBytes > mtu) {  // send in multiple runs
                                if (mtu > swCapacity)         // cannot fit in buffer, wait
                                    continue;
                                else {                        // can send a packet of size mtu
                                    byte[] data = new byte[mtu];
                                    if (fileReader.read(data) != -1) { // read successfully
                                        ArrayList<Integer> flagBits = new ArrayList<>();
                                        flagBits.add(ACK);
                                        byte[] packet = createPacket(lastSent + 1, data, flagBits);

                                        DatagramPacket udpPacket = new DatagramPacket(packet, packet.length, InetAddress.getByName(remoteIP), receiverPort);
                                        senderSocket.send(udpPacket);
                                        setTimeOut(lastSent + 1, packet);
                                        output(packet, true);
                                        updateAfterSend(packet);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

        }
    }
}
