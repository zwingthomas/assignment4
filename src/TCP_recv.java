import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class TCP_recv {

    private DatagramSocket socket;
    private int mtu;
    private int sws;

    public TCP_recv(DatagramSocket socket, int mtu, int sws) {
        this.socket = socket;
        this.mtu = mtu;
        this.sws = sws;
    }

    public void receive() throws IOException {
        byte[] buf = new byte[this.mtu];
        boolean running = true;
        TCP_segm received = null;
        Writer wr = new FileWriter("r.txt");

        //TODO: Handle connection starting with SYN

        //TODO: Handle connection end with FIN
        while (running) {
            //receive the data
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            int remote_port = packet.getPort();
            InetAddress remote_addr = packet.getAddress();
            TCP_segm recv = new TCP_segm(0, 0, 0, 0, (short) 0, null, "D");
            recv = recv.deserialize(packet.getData());
            System.out.println("\n\nSegment recieved: \n" + recv.toString()+"\n\n");
            byte[] recvData = recv.getData();


            //write the data
            System.out.println("packet.length: " + packet.getLength());
            System.out.println("recv.length: " + recv.length);
            System.out.println("recv data.length: " + recv.getData().length);
            String str = new String(recvData, 0, recv.length/4);
            System.out.println("What we are writing to the file: \n" + str + "\n\n");
            wr.write(str);
            wr.flush();


            //send ACK
            byte[] emptyBuf = new byte[0];
            recv.setData(emptyBuf);
            recv.setFlag("A");
            recv.setLength(0);
            recv.serialize();
            packet = new DatagramPacket(recv.serialize(), recv.length, remote_addr, remote_port);
            //System.out.println("receive length: " + recv.length);
            //System.out.println("Ack back: " + recv.toString());
            socket.send(packet);
        }
    }

    public void sendACK(TCP_segm recv, DatagramPacket recvPack) throws IOException {
        TCP_segm s = new TCP_segm(-1, recv.getAcknowlegment(), System.currentTimeMillis(), 0, (short) 0, null, "A");
        DatagramSocket socket = null;
        socket = new DatagramSocket(recvPack.getPort());

        DatagramPacket packet = new DatagramPacket(s.serialize(), s.totalLength, recvPack.getAddress(), recvPack.getPort());
        socket.send(packet);
    }
}