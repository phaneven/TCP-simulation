
import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;



class Resender extends Thread {
	private DatagramSocket clientSocket;
	private int timeout;
	private int SeqNum;
	private int AckNum;
	private Sender s;
	private byte[] fileBuffer;
	private int MSS;
	private InetAddress IPAddress;
	private int port;
	private int Window;
	private boolean hasShacked;
	private double pdrop;
	private long seed;
	private Random random;
	private boolean Finish;
	public Resender(DatagramSocket clientSocket, int timeout, int SeqNum, int AckNum, Sender s, byte[] fileBuffer, int MSS, 
					InetAddress IPAddress, int port, int Window,
					boolean hasShacked, double pdrop, long seed, Random random, boolean Finish) {
		this.clientSocket = clientSocket;
		this.timeout = timeout;
		this.SeqNum = SeqNum;
		this.AckNum = AckNum;
		this.s = s;
		this.fileBuffer = fileBuffer;
		this.MSS = MSS;
		this.IPAddress = IPAddress;
		this.port = port;
		this.Window = Window;
		this.hasShacked = hasShacked;
		this.pdrop = pdrop;
		this.seed = seed;
		this.random = random;
		this.Finish = Finish;
	}
	public void run () {
		// send the segment
		byte[] SendCurrentBuffer = new byte[MSS];
		if (fileBuffer.length - SeqNum + 1 > MSS ) {
			System.arraycopy(this.fileBuffer, SeqNum-1, SendCurrentBuffer, 0, SendCurrentBuffer.length);
		} else {
			System.arraycopy(this.fileBuffer, SeqNum-1, SendCurrentBuffer, 0, fileBuffer.length - SeqNum + 1);
		}
		
		Sender.Segment segment = s.new Segment(SeqNum, AckNum, false, false, false, SendCurrentBuffer, Window);
		DatagramPacket sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, IPAddress, port);
		
		ClientListener listener = new ClientListener(clientSocket, timeout, SeqNum, AckNum ,s, fileBuffer, MSS, IPAddress, port, Window,hasShacked, pdrop, seed, random, Finish);
		
		
		if (Sender.PLD (hasShacked, seed, pdrop, random) == true) {
			try {
				clientSocket.send(sendPacket);
				String log = "snd	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	D	" +	SeqNum + "	" + (sendPacket.getLength() - s.fetch_headerLength(sendPacket)) + "	" + AckNum;
				s.Q.offer(log+'\n');
				listener.start();
				System.out.println(log);
				s.nRetransmitted ++;
				
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else {
			String log = "drop	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	D	" +	SeqNum + "	" + (sendPacket.getLength() - s.fetch_headerLength(sendPacket)) + "	" + AckNum;
			s.Q.offer(log+'\n');
			listener.start();
			System.out.println(log);
			s.nDrop ++;
		}
		
	}
	
}

class ClientListener extends Thread {
	private DatagramSocket clientSocket;
	private int timeout;
	private int SendBase;
	private int nextSeqNum;
	private int nextAckNum;
	private Sender s;
	private byte[] fileBuffer;
	private int MSS;
	private InetAddress IPAddress;
	private int port;
	private int Window;
	private boolean hasShacked;
	private double pdrop;
	private long seed;
	private Random random;
	private int MWS;
	private boolean Finish;
	public ClientListener(DatagramSocket clientSocket, int timeout, int SendBase, int SendAck, Sender s, byte[] fileBuffer, int MSS, 
							InetAddress IPAddress, int port, int Window,
							boolean hasShacked, double pdrop, long seed, Random random, boolean Finish) {
		this.clientSocket = clientSocket;
		this.timeout = timeout;
		this.SendBase = SendBase;
		this.nextSeqNum = SendBase;
		this.nextAckNum = SendAck;
		this.s = s;
		this.fileBuffer = fileBuffer;
		this.MSS = MSS;
		this.IPAddress = IPAddress;
		this.port = port;
		this.Window = Window;
		this.hasShacked = hasShacked;
		this.pdrop = pdrop;
		this.seed = seed;
		this.random = random;
		this.MWS = Window;
		this.Finish = Finish;
	}
	@Override
	public void run() {
		// TODO Auto-generated method stub
		
		
		byte[] buffer = new byte[1024]; // has no payload, so use the default buffer size headerSize 20
		DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
		try {
			this.clientSocket.setSoTimeout(timeout);
			int duplicate_counter = 1; // count duplicate ACKs
			while (!Finish) {
				
				clientSocket.receive(receivePacket);
				int RecSeqNum = Sender.fetch_SeqNum(receivePacket);
				int RecAckNum = Sender.fetch_AckNum(receivePacket);
				String log = "rcv	" +  (double)(System.nanoTime()-s.start)/1000000.0 + "	A	" + RecSeqNum + "	" + 0 + "	" + RecAckNum;
				s.Q.offer(log+'\n');
				System.out.println (log); 
				
				if (RecAckNum > fileBuffer.length + 1) {  // successfully received the last segment
					Finish = true;
					s.Finish = true;
					s.finishSeqNum = RecAckNum;
					s.finishAckNum = RecSeqNum;
					break;
				} 
				
				if (RecAckNum > nextSeqNum) { // indicates that any segments before all all received
					nextSeqNum = RecAckNum;
					if (Window != 0) {
						this.clientSocket.setSoTimeout(timeout);
					} else {
						Window = MWS;  // reset window to accept next window's segmentss
					}
					duplicate_counter = 1;
				} else {
					s.nDuplicatedAck ++;
					duplicate_counter ++;
					if (duplicate_counter == 3) {
						//recall Resender thread
						System.out.println("resend due to duplicate package");
						Resender resender = new Resender(clientSocket, timeout, nextSeqNum, nextAckNum, s, this.fileBuffer, MSS, 
									IPAddress, port, Window,
									hasShacked, pdrop, seed, random, Finish);
						resender.start();
						Thread.currentThread().interrupt();
						return;
						
					}		
				}	
			}
			
		} catch (SocketTimeoutException e) {
			// if timeout is catched, recreate the oldest unAcked segment and resend it => recall a thread in charge of resend lose packet
			if(!Finish) {
				System.out.println("resend due to time out");
				Resender resender = new Resender(clientSocket, timeout, nextSeqNum, nextAckNum, s, this.fileBuffer, MSS, 
						IPAddress, port, Window,
						hasShacked, pdrop, seed, random, Finish);
				resender.start();
			}
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 
		
	}	
}



public class Sender {	
	public volatile boolean Finish = false;
	public volatile int finishSeqNum = 0;
	public volatile int finishAckNum = 0;
	public volatile long start = System.nanoTime();	
	public volatile Queue<String> Q = new LinkedList<String>();
	public volatile int nDrop = 0;
	public volatile int nRetransmitted = 0;
	public volatile int nDuplicatedAck = 0;
	public static void main(String args[]) throws Exception {
		/************** all arguments*****************/
		InetAddress IPAddress = InetAddress.getByName(args[0]);
		int port = Integer.parseInt(args[1]);
		RandomAccessFile rf = new RandomAccessFile(args[2], "r");
//		File f = new File(args[2]);
		int MWS = Integer.parseInt(args[3]); // MWS
		int MSS = Integer.parseInt(args[4]); // MSS
		int timeout = Integer.parseInt(args[5]);
		double pdrop = Double.parseDouble(args[6]);
		long seed = Long.parseLong(args[7]);
		/*********************************************/
		Random random = new Random(seed);
		
		int Window = MWS;
		boolean hasShacked = false;
		Sender s = new Sender();
	
		int InitialSeqNum = 0;
		int NextSeqNum = InitialSeqNum;
		int SendBase = InitialSeqNum;
		int RecSeqNum = 0;
		int RecAckNum = 0;
		
		DatagramSocket clientSocket = new DatagramSocket();
		clientSocket.setSoTimeout(timeout);
		
		// build connection: three way handshake 
		while (!hasShacked) {
			// send SYN
			if (SendBase == InitialSeqNum) {
				Sender.Segment segment = s.new Segment(SendBase, 0, false, true, false, null, Window);
				DatagramPacket sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, IPAddress, port);
				clientSocket.send(sendPacket);
				String log = "snd	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	S	" +	SendBase + "	" + 0 + "	" + (RecSeqNum+1);
				s.Q.offer(log+'\n');
				System.out.println(log);
				NextSeqNum++;
			}
			// receive SYN+ACK
			byte[] buffer = new byte[1024]; // has no payload, so use the default buffer size headerSize 20
			DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
			try {
				clientSocket.receive(receivePacket);
				RecSeqNum = fetch_SeqNum(receivePacket);
				RecAckNum = fetch_AckNum(receivePacket);
				String log = "rcv	" +  (double)(System.nanoTime()-s.start)/1000000.0 + "	SA	" + RecSeqNum + "	" + 0 + "	" + RecAckNum;
				s.Q.offer(log+'\n');
				System.out.println (log);
				SendBase++;
				// send ACK
				if (SendBase == InitialSeqNum + 1) {
					Sender.Segment segment = s.new Segment(SendBase, RecSeqNum+1, true, false, false, null, Window);
					DatagramPacket sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, IPAddress, port);
					clientSocket.send(sendPacket);
					log = "snd	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	ACK	" +	SendBase + "	" + 0 + "	" + (RecSeqNum+1);
					s.Q.offer(log+'\n');
					System.out.println (log);
					NextSeqNum++;
				}
				hasShacked = true;
//				System.out.println ("connection established!");
			}
			catch (SocketTimeoutException e){
				System.out.println("time out");
			}
		}
		System.out.println("start to start send data...");
		
		// prepare for sending data
		byte[] fileBuffer = ReadFileToBuffer(rf); // read all file's data into buffer
		// open a thread keep listening packet from receiver
		ClientListener listener = new ClientListener(clientSocket, timeout, SendBase, RecSeqNum+1 ,s, fileBuffer, MSS, 
									IPAddress, port, Window,hasShacked, pdrop, seed, random, s.Finish);
		listener.start();	
		
		int SendBasePtr = 0;
		boolean flag = false; // finish flag
		int nSegments = 0;
		
		while (true) {
			
			// send all segment in a Window
			for (int j=0; j < MWS; ++j) {
				nSegments ++;
				byte[] SendCurrentBuffer = new byte[MSS];
				if (fileBuffer.length - SendBasePtr > MSS) {
					System.arraycopy(fileBuffer, SendBasePtr, SendCurrentBuffer, 0, SendCurrentBuffer.length);
				} else {
					System.arraycopy(fileBuffer, SendBasePtr, SendCurrentBuffer, 0, fileBuffer.length - SendBasePtr);
				}
				
				Sender.Segment segment = s.new Segment(SendBase, RecSeqNum+1, false, false, false, SendCurrentBuffer, Window);
				DatagramPacket sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, IPAddress, port);
				
				if (PLD (hasShacked, seed, pdrop, random) == true) {
					String log = "snd	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	D	" +	SendBase + "	" + (segment.segment_length-segment.headerSize_) + "	" + (RecSeqNum+1);
					s.Q.offer(log+'\n');
					System.out.println (log);
					clientSocket.send(sendPacket);
				} else {
					String log = "drop	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	D	" +	SendBase + "	" + (segment.segment_length-segment.headerSize_) + "	" + (RecSeqNum+1);
					s.Q.offer(log+'\n');
					s.nDrop ++;
					System.out.println (log);
				}
				
				SendBasePtr += MSS;	
				SendBase += MSS; 
				if (SendBasePtr > fileBuffer.length) {
					flag = true;
					break;
				}	
			}
			if (flag) {break;}
			// listening for ack segment
		} 
		while(true) {
			if (s.Finish) {
				System.out.println("start to finish");
				break;
			}
		}
		if (s.Finish) {
			// set FIN flag and sent
			Sender.Segment segment = s.new Segment(s.finishSeqNum, s.finishAckNum, false, false, true, null, Window);
			DatagramPacket sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, IPAddress, port);
			clientSocket.send(sendPacket);
			String log = "snd	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	FIN	" +	s.finishSeqNum + "	" + 0 + "	" + s.finishAckNum;
			s.Q.offer(log+'\n');
			System.out.println (log);
			// receive ACK
			byte[] buffer = new byte[1024]; // has no payload, so use the default buffer size headerSize 20
			DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
			try {
				clientSocket.receive(receivePacket);
				RecSeqNum = fetch_SeqNum(receivePacket);
				RecAckNum = fetch_AckNum(receivePacket);
				log = "rcv	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	ACK	" +	RecSeqNum + "	" + 0 + "	" + RecAckNum;
				s.Q.offer(log+'\n');
				System.out.println (log);
				
			}
			catch (SocketTimeoutException e){
				System.out.println("time out");
			}
			// receive FIN
			try {
				clientSocket.receive(receivePacket);
				RecSeqNum = fetch_SeqNum(receivePacket);
				RecAckNum = fetch_AckNum(receivePacket);
				log = "rcv	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	FIN	" +	RecSeqNum + "	" + 0 + "	" + RecAckNum;
				s.Q.offer(log+'\n');
				System.out.println (log);
			}
			catch (SocketTimeoutException e){
				System.out.println("time out");
			}
			
			// send with Ack flag set
			segment = s.new Segment(RecAckNum, RecSeqNum + 1, true, false, false, null, Window);
			sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, IPAddress, port);
			clientSocket.send(sendPacket);
			log = "snd	"+ (double)(System.nanoTime()-s.start)/1000000.0 + "	ACK	" +	RecAckNum + "	" + 0 + "	" + (RecSeqNum + 1);
			s.Q.offer(log+'\n');
			System.out.println (log);
		}
		File file =new File("Sender_log.txt");
		FileWriter fileWritter = new FileWriter(file.getName());
		BufferedWriter bufferWritter = new BufferedWriter(fileWritter); // write to the log file
		while (!s.Q.isEmpty()) {
			bufferWritter.write(s.Q.poll());
		}
		bufferWritter.write("Amount of Data Transferred (in bytes): " + fileBuffer.length + '\n');
		bufferWritter.write("Number of Data Segments Sent (excluding retransmissions): " + nSegments + '\n');
		bufferWritter.write("Number of Packets Dropped (by the PLD module): " + s.nDrop + '\n');
		bufferWritter.write("Number of Retransmitted Segments: " + s.nRetransmitted + '\n');
		bufferWritter.write("Number of Duplicate Acknowledgements received: " + s.nDuplicatedAck + '\n');
		
		bufferWritter.close();
		
	
	}
	/*
	 *  header structure: 
	 *  4 bytes sequence number
	 *  4 bytes acknowledge bytes
	 *  1 byte for headerSize, 1 byte for flag, 2 bytes window
	 *  flag last 6 bits: [URG, ACK, PSH, RST,SYN, FIN], only using ACK , SYN and FIN here
	 */
	public class Segment {
		// constructor
		Segment (int SeqNum, int AckNum, boolean ACK, boolean SYN, boolean FIN, byte[] d, int window_size) {
			this.SeqNum_ = SeqNum;
			this.AckNum_ = AckNum;
			this.ACK_ = ACK;
			this.SYN_ = SYN;
			this.FIN_ = FIN;
			this.data = d;
			if (data != null) {this.data_length = d.length;}
			this.segment_length = this.data_length + this.headerSize_;
			this.window_size = window_size;
		}
		public int get_SeqNum (){
			return SeqNum_;
		}
		public int get_AckNum (){
			return AckNum_;
		}
		public boolean get_AckFlag () {
			return ACK_;
		}
		public boolean get_SynFlag () {
			return SYN_;
		}
		public boolean get_FinFlag () {
			return FIN_;
		}
		public int get_windowSize() {
			return window_size;
		}
		
		// produce a byte array type header
		public byte[] createHeader () {
			byte[] h = new byte[headerSize_];
			h[9] = 0; // initiate the flag byte
			// add SeqNum to the header segment
			h[0] = (byte)((SeqNum_ >> 24) & 0xFF);
			h[1] = (byte)((SeqNum_ >> 16) & 0xFF);
			h[2] = (byte)((SeqNum_ >> 8) & 0xFF);
			h[3] = (byte)(SeqNum_ & 0xFF);
			// add AckNum to the header segment
			h[4] = (byte)((AckNum_ >> 24) & 0xFF);
			h[5] = (byte)((AckNum_ >> 16) & 0xFF);
			h[6] = (byte)((AckNum_ >> 8) & 0xFF);
			h[7] = (byte)(AckNum_ & 0xFF);
			// set header length
			h[8] = (byte) headerSize_;
			// set Ack flag in the header segment
			if (ACK_ == true) {
				h[9] += 1<<4;
			}
			if (SYN_ == true) {
				h[9] += 1<<1;
			}
			if (FIN_ == true) {
				h[9] += 1;
			}
			h[10] = (byte)((window_size >> 8) & 0xFF);
			h[11] = (byte)(window_size & 0xFF);
			return h;
		}
		// produce a STP segment
		public byte[] createSegment () {
			byte[] h = createHeader();
			if (data_length != 0) {
				byte[] segment = new byte[data_length + headerSize_];
				System.arraycopy(h, 0, segment, 0, headerSize_);
				System.arraycopy(data, 0, segment, headerSize_, data_length);
				return segment;
			} else {
				return h;
			}
		}
		
		private	int SeqNum_;
		private	int AckNum_;
		private	boolean ACK_ = false;
		private	boolean SYN_ = false;
		private	boolean FIN_ = false; 
		private int headerSize_ = 12;
		private byte[] data;
		private int data_length = 0;
		private int window_size;
		int segment_length = 0;
	}
	
	
	
	// fetch SeqNum from receive package
	public static int fetch_SeqNum (DatagramPacket P) {
		int SeqNum = 0;
		SeqNum += (P.getData()[3] & 0xff);
		SeqNum += (P.getData()[2] & 0xff) << 8;
		SeqNum += (P.getData()[1] & 0xff) << 16;
		SeqNum += (P.getData()[0] & 0xff) << 24;
		return SeqNum;
	}
		
	// fetch AckNum from receive pack
	public static int fetch_AckNum (DatagramPacket P) {
		int AckNum = 0;
		AckNum += (P.getData()[7] & 0xff);
		AckNum += (P.getData()[6] & 0xff) << 8;
		AckNum += (P.getData()[5] & 0xff) << 16;
		AckNum += (P.getData()[4] & 0xff) << 24;
		return AckNum;
	}
	
	// fetch window size
	public static int fetch_windowSize (DatagramPacket P) {
		int windowSize = 0;
		windowSize += P.getData()[11] & 0xff;
		windowSize += (P.getData()[10] & 0xff) << 8;
		return windowSize;
	}
	
	// fetch header length
	public static int fetch_headerLength (DatagramPacket P) {
		return (int)P.getData()[8];
	}
	
	static byte[] ReadFileToBuffer (RandomAccessFile rf) throws IOException{
		byte[] buffer = new byte[(int) rf.length()];
		rf.read(buffer);
		return buffer;
	}
	
	// PLD
	static boolean PLD (boolean isShacked, long seed, double pdrop, Random random) {
		
		if (!isShacked) {
			return true;
		}else{
			float rd = random.nextFloat();
//			System.out.println(rd);
			if (rd> pdrop) {
				return true;
			}else {
				return false;
			}
		}
		
	}
	
}
