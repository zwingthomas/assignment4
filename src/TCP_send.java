import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class TCP_send extends Thread {

    private DatagramSocket socket;
    private InetAddress remote_IP;
    private int remote_port;
    private String file_name;
    private int mtu;
    protected int sws;
    private char flag;
    private int sequenceSender;

    private Map<Integer, Long> sequence_timeout_map;

    //variables for timeOut calculation
    long ERTT;
    long EDEV;
    private long timeout;

    public TCP_send(DatagramSocket socket, String remote_IP, int remote_port, int sws, char flag, String file_name) throws UnknownHostException {
        this.socket = socket;
        InetAddress addr = InetAddress.getByName(remote_IP);
        this.remote_IP = addr;
        this.remote_port = remote_port;
        this.flag = flag;
        this.timeout = 5000000000L;
        this.sws = sws;
        this.file_name = file_name;
    }

    public void send(ArrayList<TCP_segm> segmArr) throws InterruptedException {

        HashMap<Integer, TCP_segm> inTransit = new HashMap<>();
        ReentrantLock lock = new ReentrantLock();
        Thread sendData = new Thread(new SendDataRunnable(segmArr, this, lock, inTransit));
        //Thread retransmit = new Thread(new SendDataRunnable(segmArr, this, lock, inTransit));

        sequence_timeout_map = Collections.synchronizedMap(new HashMap<>());

        Timer t = new Timer(true);

        t.scheduleAtFixedRate(
                new TimerTask() {
                    public void run() {
                        check_old_timestamps(inTransit);
                    }
                }
                , 0, 1);

        sendData.start();


        int ackNum = 0;
        while (ackNum < segmArr.size()) {
            try {
                TCP_segm ack = receiveAck();
                System.out.println("rcv " + System.nanoTime() / 1000000000 + " " + ack.getFlag() +
                        " " + ack.getSequence() + " " + ack.getData().length + " " + ack.getAcknowlegment());
                computeTimeout(ack.timeStamp, ack.acknowledgment - 1);

//                sequence_timeout_map.get(ack.acknowledgment - 1);
//                if ()
                synchronized(sequence_timeout_map) {
                    sequence_timeout_map.remove(ack.acknowledgment - 1);
                }
                lock.lock();
                inTransit.remove((ack.acknowledgment - 1));
                ackNum++;
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
        sendData.join();
    }

    public void handshake(int initSeqNum) throws IOException {
        //Send SYN
        sendNoData("S", initSeqNum);

        //Receive SYNACK
        TCP_segm ack = receiveAck();

        //Send ACK
        sendNoData("A", initSeqNum + 1);

    }

    public void connectionTeardown() throws IOException {
        //Send FIN
        byte[] buf = file_name.getBytes();
        TCP_segm finalSeg = new TCP_segm(this.sequenceSender, 0, System.nanoTime(), file_name.length(), (short) 0, buf, "F");
        sendData(finalSeg);

        //Receive ACK
        TCP_segm ack = receiveAck();

        //Receive FINACK
        TCP_segm finAck = receiveAck();

        //Send ack
        sendNoData("A", finAck.sequence + 1);
        System.exit(0);

    }

    public void sendData(TCP_segm segment) throws IOException {
        segment.setTimeStamp(System.nanoTime());
        segment.serialize();
        DatagramPacket packet = new DatagramPacket(segment.serialize(), 0, segment.getLength() + 24, this.remote_IP, this.remote_port);
        synchronized(sequence_timeout_map) {
            sequence_timeout_map.put(segment.sequence, segment.timeStamp + System.nanoTime());
        }
        //System.out.println("Sending_______________");
        //System.out.println(segment.toString() + "\n");
        socket.send(packet);
    }

    public void sendNoData(String flag, int seqNum) throws IOException {
        int size = 0;
        byte[] buf = new byte[size];
        TCP_segm tcpSegm = new TCP_segm(seqNum, 0, System.nanoTime(), 0, (short) 0, buf, flag);
        tcpSegm.serialize();
        //System.out.println("Sending_______________");
        //System.out.println(tcpSegm.toString() + "\n");
        DatagramPacket packet = new DatagramPacket(tcpSegm.serialize(), 0, tcpSegm.getLength() + 24, this.remote_IP, this.remote_port);
        socket.send(packet);
    }

    public TCP_segm receiveAck() throws IOException {
        byte[] buf = new byte[24];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        TCP_segm ack = new TCP_segm(0, 0, 0, 0, (short) 0, null, "E");
        ack = ack.deserialize(packet.getData());
        //System.out.println("\t\t\t\t\t\tRTT in RECV" + (System.nanoTime() - ack.timeStamp));
        //System.out.println("Recving_______________");
        //System.out.println(ack.toString() + "\n");
        return ack;
    }

    public void computeTimeout(long timestamp, int sequenceNum) {
        //System.out.println("/t/t/t/t/tCOMPUTE TIMEOUT CALLED: " + timestamp + " || " + sequenceNum);
        double a = 0.875;
        double b = 0.75;
        if (sequenceNum == 0) {
            this.ERTT = System.nanoTime() - timestamp;
            this.EDEV = 0L;
            this.timeout = 2L * this.ERTT;
        } else {
            // System.out.println("computeTimeout________________");
            long SRTT = System.nanoTime() - timestamp;
            // System.out.println("SRTT: " + SRTT);
            // System.out.println("ERTT before: " + ERTT);
            long SDEV = Math.abs(SRTT - this.ERTT);
            // System.out.println("SDEV: " + SDEV);
            this.ERTT = (long) (a * this.ERTT) + (long) ((1 - a) * SRTT);
            // System.out.println("ERTT: " + this.ERTT);
            this.EDEV = (long) (b * this.EDEV) + (long) ((1 - b) * SDEV);
            // System.out.println("EDEV: " + EDEV);
            this.timeout = this.ERTT + (4L * this.EDEV);
            // System.out.println("timeout: " + this.timeout);
        }
        // System.out.println("TIMEOUT TIME: " + this.timeout);
    }

    public void check_old_timestamps(HashMap<Integer, TCP_segm> inTransit) {
        ArrayList<Integer> to_retransmit = new ArrayList<>();
        synchronized(sequence_timeout_map) {
            for (Integer seq_num : sequence_timeout_map.keySet()) {
                if (sequence_timeout_map.get(seq_num) < System.nanoTime()) {
                    try {
                        sendData(inTransit.get(seq_num));
                        inTransit.put(seq_num, inTransit.get(seq_num));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

}

class SendDataRunnable implements Runnable {
    protected ArrayList<TCP_segm> segmArr;
    public final TCP_send sender;
    ReentrantLock lock;
    HashMap<Integer, TCP_segm> inTransit;

    public SendDataRunnable(ArrayList<TCP_segm> segmArr, TCP_send sender, ReentrantLock lock, HashMap<Integer, TCP_segm> inTransit) {
        this.segmArr = segmArr;
        this.sender = sender;
        this.inTransit = inTransit;
        this.lock = lock;
    }

    public void run() {
        int segsSent = 0;
        while (segsSent < segmArr.size()) {
            try {
                lock.lock();
                if (inTransit.size() < sender.sws) {
                    segmArr.get(segsSent).setTimeStamp(System.nanoTime());
                    //segmArr[segsSent].startTimer(sender);
                    sender.sendData(segmArr.get(segsSent));
                    System.out.println("snd " + System.nanoTime() / 1000000000 + " " + segmArr.get(segsSent).getFlag() +
                            " " + segmArr.get(segsSent).getSequence() + " " + segmArr.get(segsSent).getData().length + " " + segmArr.get(segsSent).getAcknowlegment());
                    inTransit.put(segmArr.get(segsSent).sequence, segmArr.get(segsSent));
                    segsSent++;
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }
}













































