package lejos.ev3.tools;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

import lejos.hardware.BrickFinder;
import lejos.hardware.BrickInfo;

/**
 * Contains the logic for connecting to the remote console stream from the EV3.
 * Can be used by different user interfaces.
 * 
 * @author Roger Glassey, Lawrie Griffiths and Andy Shaw
 *
 */
public class ConsoleViewComms
{
    private static final int MODE_SWITCH = 0xff;
    private static final int MODE_LCD = 0x0;
    
    private static final int PORT = 8001;
    
    private InputStream is = null;

    private ConsoleViewerUI viewer;
    private Reader reader;
    private boolean daemon;
    private Socket sock; 

    public ConsoleViewComms(ConsoleViewerUI viewer, boolean daemon)
    {
    	this.daemon = daemon;
        this.viewer = viewer;
        reader = new Reader();
        reader.setDaemon(daemon);
        reader.start();
    }
    
    /**
     * Connect to RConsole on the NXT uusing either USB or Bluetooth
     * 
     * @param name the name of the NXT or null
     * @param address the address of the NXT or null
     * @param useUSB use USB if true, else use Bluetooth
     * @param lcd Request NXT LCD display contents
     * @return true iff the connection was successful
     */
    public boolean connectTo(String name, String address, boolean useUSB, boolean lcd)
    {
    	return connectTo(name);
    }

    /**
     * Connect to RConsole on the NXT using the specified protocols
     * 
     * @param name the name of the NXT or null
     * @param address the address of the NXT or null
     * @param protocol USB or Bluetooth or both
     * @param lcd Request NXT LCD display contents
     * @return true iff the connection was successful
     */
    public boolean connectTo(String name, String address, int protocol, boolean lcd)
    {
    	if (name == null && address == null) {
    		try {
				BrickInfo[] bricks = BrickFinder.discover();
				if (bricks.length == 0) {
					System.err.println("Failed to find an EV3");
					return false;
				}
				name = bricks[0].getIPAddress();
			} catch (Exception e) {
				System.err.println("No EV3s discovered");
				return false;
			}
    	}
    	return connectTo(name != null ? name : address);
    }
    
    private boolean connectTo(String name) {
    	try {
			sock = new Socket(name,PORT);
			is = sock.getInputStream();
	        viewer.connectedTo(name, name);
	        viewer.logMessage("Connected to " + name);
	    	reader.setConnected(true);
			return true;
		} catch (IOException e) {
			System.err.println("Failed to connect to " + name + ": " + e);
			return false;
		}
    }
    
    /**
     * Close the connection
     */
    public void close() {
    	//System.out.println("Closing the connection");;
    	try {
			if (sock != null) sock.close();
		} catch (IOException e) {
			System.err.println("Socket failed to close");
		}
    	reader.setConnected(false);
    }

    /**
     * Wait for the console session to end
     */
    public void waitComplete()
    {
        if (!this.daemon && reader != null)
            try {
                reader.join();
            } catch(InterruptedException e)
            {

            }
    }
    /**
     * Thread to read the RConsole data and send it to the viewer append method
     */
    private class Reader extends Thread
    {
        private byte [] lcdBuffer = new byte[2944];
        private boolean connected = false;


        private int processLCDData() throws IOException
        {
            int cnt = 0;

            while (cnt < lcdBuffer.length)
            {
                int len = is.read(lcdBuffer, cnt, lcdBuffer.length - cnt);
                if (len < 0) return -1;
                cnt += len;
            }
            viewer.updateLCD(lcdBuffer);
            return cnt;
        }

        
        public synchronized void setConnected(boolean c)
        {
        	this.connected = c;
        	this.notifyAll();
        }

        @Override
        public void run()
        {
            while (true)
            {              
            	synchronized (this)
            	{
	            	while (!connected)
	            	{
	            		try
						{
							this.wait();
						}
						catch (InterruptedException e)
						{
							e.printStackTrace();
							return;
						}
	            	}
            	}
                
                try
                {
                    int input;
                    ioloop:while ((input = is.read()) >= 0)
                    {
                        if (input == MODE_SWITCH)
                        {
                            // Extended data types, first byte tells us the type
                            switch(is.read())
                            {
                                case MODE_LCD:
                                    if (processLCDData()< 0) break ioloop;
                                    break;
                            }
                            //System.out.println("Got 255 marker");
                        }
                        else
                            viewer.append("" + (char) input);
                    }
                    close();
                    if (!daemon) return;
                }
                catch (IOException e)
                {
                    close();
                    if (!daemon) return;
                }
            }
        }
    }
}

