package lejos.ev3.tools;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.rmi.Naming;
import java.util.ArrayList;
import java.util.Collection;

import lejos.remote.ev3.RMIMenu;
import lejos.remote.nxt.FileInfo;
import lejos.remote.nxt.NXTCommConnector;
import lejos.remote.nxt.NXTConnection;
import lejos.remote.nxt.SocketConnector;
import lejos.robotics.RangeReading;
import lejos.robotics.RangeReadings;
import lejos.robotics.geometry.Point;
import lejos.robotics.localization.MCLPoseProvider;
import lejos.robotics.mapping.LineMap;
import lejos.robotics.mapping.NavigationModel;
import lejos.robotics.mapping.SVGMapLoader;
import lejos.robotics.navigation.DestinationUnreachableException;
import lejos.robotics.navigation.Move;
import lejos.robotics.navigation.Pose;
import lejos.robotics.navigation.Waypoint;
import lejos.robotics.pathfinding.AstarSearchAlgorithm;
import lejos.robotics.pathfinding.FourWayGridMesh;
import lejos.robotics.pathfinding.Node;
import lejos.robotics.pathfinding.NodePathFinder;
import lejos.robotics.pathfinding.PathFinder;
import lejos.robotics.pathfinding.RandomPathFinder;
import lejos.robotics.pathfinding.ShortestPathFinder;
import lejos.utility.Delay;

/**
 * The PCNavigationModel holds all the navigation data that is transmitted as events,
 * to and from an EV3 brick.
 * 
 * It has methods to generate events, and a Receiver thread to receive events from the EV3.
 * 
 * There is a NavigationPanel associated with the model. Whenever data in the model is updated,
 * the NavigationPanel is repainted with the new data.
 * 
 * @author Lawrie Griffiths
 *
 */
public class PCNavigationModel extends NavigationModel {
	protected MapApplicationUI panel;
	protected int closest = -1;
	protected boolean connected = false;
	protected RangeReadings particleReadings = new RangeReadings(3);
	protected float weight;
	protected FourWayGridMesh mesh;
	protected int gridSpace = 39;
	protected int clearance = 20;
	protected AstarSearchAlgorithm alg = new AstarSearchAlgorithm();
	protected Collection<Node> nodes;
	protected Node start, destination;
	protected PathFinder pf;
	protected ArrayList<Move> moves = new ArrayList<Move>();
	protected ArrayList<Pose> poses = new ArrayList<Pose>();
	protected ArrayList<Point> features = new ArrayList<Point>();
	protected ArrayList<Waypoint> waypoints = new ArrayList<Waypoint>();
	protected Waypoint reached;
	protected float voltage = 0;
	
	private Thread receiver = new Thread(new Receiver());
	private boolean running = true;
	
	private RMIMenu menu;
	
	protected static final int STARTUP_DELAY = 10000;
	protected static final int CONNECTION_DELAY = 5000;
	protected static final int MAX_CONNECTION_ATTEMPTS = 5;
	
	/**
	 * Create the model and associate the navigation panel with it
	 * 
	 * @param panel the NavigationPanel
	 */
	public PCNavigationModel(MapApplicationUI panel) {
		this.panel = panel;
	}
	
	/**
	 * Set the parameter for the 4-way mesh
	 * 
	 * @param gridSpace the spacing of the mesh
	 * @param clearance the clearance from the walls
	 */
	public void setMeshParams(int gridSpace, int clearance) {
		this.gridSpace = gridSpace;
		this.clearance = clearance;
	}
	
	/**
	 * Get the MCLPoseProvider associated with this model
	 * 
	 * @return the MCLPoseProvider
	 */
	public MCLPoseProvider getMCL() {
		return mcl;
	}
	
	/**
	 * Set an MCLPOseProvider for this model
	 * 
	 * @param mcl the MCLPoseProvider
	 */
	public void setMCL(MCLPoseProvider mcl) {
		this.mcl = mcl;
	}
	
	/**
	 * Get the last move made by the robot
	 * 
	 * @return the Move object
	 */
	public Move getLastMove() {
		return lastMove;
	}
	
	/**
	 * Get the moves made since the last setPose
	 * 
	 * @return an ArrayList of Move objects
	 */
	public ArrayList<Move> getMoves() {
		return moves;
	}
	
	/**
	 * Get the list of features
	 * 
	 * @return the features as an array of points
	 */
	public ArrayList<Point> getFeatures() {
		return features;
	}
	
	/**
	 * Get the list of waypoints
	 * 
	 * @return the list of waypoints
	 */
	protected ArrayList<Waypoint> getWaypoints() {
		return waypoints;
	}
	
	/**
	 * Get the sequence of poses of the robot from moves sent from the EV3,
	 * since the last call of setPose
	 * 
	 * @return an ArrayList of Pose objects
	 */
	public ArrayList<Pose> getPoses() {
		return poses;
	}
	
	/**
	 * Connect to the EV3, upload a program, and run it
	 * 
	 * @param ev3Name the name of the EV3
	 * @param file the name of the program file
	 */
	public void connectAndUpload(String ev3Name, File file) throws FileNotFoundException {
		try {
			menu = (RMIMenu) Naming.lookup("//" + ev3Name + "/RemoteMenu");
		} catch (Exception e) {
			panel.error("EV3 not found");
			return;
		}
		System.out.println("Connect and upload: " + file.getPath());
		if (!file.exists()) {
			panel.error(file.getAbsolutePath() + " not found");
			throw(new FileNotFoundException());
		}
		
		FileInputStream in = new FileInputStream(file);
		byte[] data = new byte[(int)file.length()];
	    try {
			in.read(data);
		    in.close();
		    System.out.println("Uploading " + file.getName());
		    menu.uploadFile("/home/lejos/programs/" + file.getName(), data);
		    System.out.println("Starting " + file.getName());
		    menu.runProgram(file.getName().replace(".jar",""));
		    Delay.msDelay(STARTUP_DELAY);
		} catch (IOException e) {
			panel.error("Failed to upload program to EV3");
		}
	}
	
	/**
	 * Check that the file exists on the EV3 with the right size
	 */
	protected boolean checkFile(File f) {
		System.out.println("Checking file " + f.getName());
		return true;
	}
	
	/**
	 * Get information for a file on the EV3
	 */
	private FileInfo getFile(String name) throws IOException {
		return null;
	}
	
	/**
	 * Set the parameters for a DifferentialPilot, send them to the EV3, and write
	 * them to the pilot.props file.
	 * 
	 * @param wheelDiameter the wheel diameter
	 * @param trackWidth the track width 
	 * @param leftMotor the left motor
	 * @param rightMotor the right motor
	 * @param reverse true iff the driving the motors in reverse drives the pilot forward
	 */
	public void setDifferentialPilotParams(float wheelDiameter, float trackWidth,
			int leftMotor, int rightMotor, boolean reverse) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(NavEvent.PILOT_PARAMS.ordinal());
				dos.writeFloat(wheelDiameter);
				dos.writeFloat(trackWidth);
				dos.writeInt(leftMotor);
				dos.writeInt(rightMotor);
				dos.writeBoolean(reverse);
				dos.flush();
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in setDifferentialPilotParams");
	    }		
	    
	}
	
	/**
	 * Set the parameter for a Range Feature Detector
	 * 
	 * @param maxDistance the distance from a feature that trifggers detection
	 * @param delay the delay between readings in microseconds
	 */
	public void setRangeFeatureParams(float maxDistance, int delay) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(NavEvent.RANGE_FEATURE_DETECTOR_PARAMS.ordinal());
				dos.writeInt(delay);
				dos.writeFloat(maxDistance);
				dos.flush();
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in setRangeFeatureParams");
	    }	
	}
	
	/**
	 * Set the patameters for a Rotating Range Scanner
	 * 
	 * @param gearRatio the ratio between motor rotation and head rotation
	 * @param headMotor the motor that drives the read (0 = A, 1 =B, 2 = C)
	 */
	public void setRotatingRangeScannerParams(int gearRatio, int headMotor) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(NavEvent.RANGE_SCANNER_PARAMS.ordinal());
				dos.writeInt(gearRatio);
				dos.writeInt(headMotor);
				dos.flush();
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in setRotatingRangeScannerParams");
	    }	
	}
	
	private void sendFloat(NavEvent navEvent, float aFloat) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(navEvent.ordinal());
				dos.writeFloat(aFloat);
				dos.flush();
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in " + navEvent.name());
	    }
	}
	
	/**
	 * Set the travel speed for the pilot.
	 * 
	 * @param speed the travel speed
	 */
	public void setTravelSpeed(float speed) {
		sendFloat(NavEvent.TRAVEL_SPEED, speed);	
	}
	
	/**
	 * Set the rotate speed for a pilot
	 * 
	 * @param speed the rotate speed
	 */
	public void setRotateSpeed(float speed) {
		sendFloat(NavEvent.ROTATE_SPEED, speed);	
	}
	
	/**
	 * Send a GET_PARTICLES event to the EV3
	 */
	public void getRemoteParticles() {
		sendEvent(NavEvent.GET_PARTICLES);		
	}
	
	/**
	 * Send a FIND_CLOSEST event to the EV3
	 */
	public void findClosest(float x, float y) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(NavEvent.FIND_CLOSEST.ordinal());
				dos.writeFloat(x);
				dos.writeFloat(y);
				dos.flush();
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in findClosest");
	    }		
	}
	
	/**
	 * Add a waypoint and send it to the EV3
	 * 
	 * @param wp the waypoint
	 */
	public void addWaypoint(Waypoint wp) {
		waypoints.add(wp);
		panel.repaint();
		sendWaypoint(NavEvent.ADD_WAYPOINT, wp);	
	}
	
	/**
	 * Generate particles for the MCLPoseProvider and send them to the EV3
	 */
	public void generateParticles() {
		mcl.generateParticles();
		particles = mcl.getParticles();
		panel.repaint();
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(NavEvent.PARTICLE_SET.ordinal());
				particles.dumpObject(dos);
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in generateParticles");
		}
	}
	
	/**
	 * Connect to the EV3
	 */
	public void connect(String ev3Name) {
		if (connected) panel.error("Already connected");
		NXTCommConnector connector = new SocketConnector();
		NXTConnection conn = null;
		for(int i=0;i<MAX_CONNECTION_ATTEMPTS;i++) {
			System.out.println("Connecting to " + ev3Name + " attempt " + (i+1));
			conn = connector.connect(ev3Name, NXTConnection.RAW);
			if (conn != null) {
				connected = true;
				break;
			} else Delay.msDelay(CONNECTION_DELAY);
		}
		
		if (!connected) {		
			panel.error("NO EV3 found");
			return;
		}
  
		if (debug) panel.log("Connected to " + ev3Name);
  
		dis = new DataInputStream(conn.openInputStream());
		dos = new DataOutputStream(conn.openOutputStream());

		// Start the receiver thread
		receiver.setDaemon(true);
		receiver.start();
		
		// Hook for actions required after connection
		panel.whenConnected();
	}
	
	/**
	 * Get the generated node
	 * 
	 * @return the collection of nodes
	 */
	public Collection<Node> getNodes() {
		return nodes;
	}
	
	/**
	 * Test if a EV3 brick is currently connected
	 * 
	 * @return true iff a EV3 brick is connected
	 */
	public boolean isConnected() {
		return connected;
	}
	
	public LineMap loadMap(String mapFileName) {
		return loadMap(mapFileName,0);
	}
	
	/**
	 * Load a line map and send it to the PC
	 * 
	 * @param mapFileName the SVG map file
	 * @return the LineMap
	 */
	public LineMap loadMap(String mapFileName, int finder) {
		try {
			if (mapFileName.length() == 0) {
				panel.error("Please specify a filename");
				return null;
			}
			File mapFile = new File(mapFileName);
			if (!mapFile.exists()) {
				String abs = mapFile.getAbsolutePath();
				// Try with .svg suffix
				mapFile = new File(mapFileName + ".svg");
				if (!mapFile.exists()) {
					panel.error(abs + " does not exist");
					return null;
				}
			}
			if (debug) panel.log("Map file is " + mapFile.getAbsolutePath());
			FileInputStream is = new FileInputStream(mapFile);
			SVGMapLoader mapLoader = new SVGMapLoader(is);
			map = mapLoader.readLineMap();
			mesh = new FourWayGridMesh(map, gridSpace,clearance);
			nodes = mesh.getMesh();
			setPathFinder(finder);
			panel.repaint();
			if (mcl != null) mcl.setMap(map);
			panel.eventReceived(NavEvent.LOAD_MAP);
			sendMap();
			return map;
		} catch (Exception ioe) {
			panel.error("Exception in loadMap:" + ioe);
			return null;
		}	
	}
	
	/**
	 * Send the map to the EV3
	 */
	public void sendMap() {
		if (!connected) return;
		if (map == null) return;
		try {
			synchronized(receiver) {
				dos.writeByte(NavEvent.LOAD_MAP.ordinal());
				map.dumpObject(dos);
			}
		} catch (IOException ioe) {
			panel.error("IOException in sendMap");
		}
		
	}
	
	public void setPathFinder(int finder) {
		if (map == null) return;
		//if (debug) panel.log("Path finder " + finder);
		if (finder == 0) {
			pf = new NodePathFinder(alg, mesh);
		} else if (finder == 1) {
			readings = new RangeReadings(3);
			// Dummy readings to set the angles
			readings.setRange(0,-45,0);
			readings.setRange(1,0,0);
			readings.setRange(2,45,0);
			
			pf = new RandomPathFinder(map, readings);
			((RandomPathFinder) pf).setMaxIterations(1000000);
		} else {
			pf = new ShortestPathFinder(map);
		}		
	}
	
	/**
	 * Send a GOTO event to the EV3
	 * 
	 * @param wp the Waypoint to go to
	 */
	public void goTo(Waypoint wp) {
		target = wp;
		sendWaypoint(NavEvent.GOTO, wp);		
	}
	
	/**
	 * Send a travel event to the EV3
	 * 
	 * @param distance the distance to travel
	 */
	public void travel(float distance) {
		sendFloat(NavEvent.TRAVEL, distance);
	}
	
	/**
	 * Send a ROTATE event to the EV3
	 * 
	 * @param angle the angle to rotate
	 */
	public void rotate(float angle) {
		sendFloat(NavEvent.ROTATE, angle);
	}
	
	/**
	 * Send an ARC event to the EV3
	 * 
	 * @param radius the radius of the arc
	 * @param angle the angle to rotate
	 */
	public void arc(float radius, float angle) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(NavEvent.ARC.ordinal());
				dos.writeFloat(radius);
				dos.writeFloat(angle);
				dos.flush();
			}
		} catch (IOException ioe) {
			panel.error("IO Exception in arc");
		}
	}
	
	/**
	 * Send a ROTATE_TO event to the EV3
	 * 
	 * @param angle the angle to rotate
	 */
	public void rotateTo(float angle) {
		sendFloat(NavEvent.ROTATE_TO, angle);
	}
	
	/**
	 * Send a GET_POSE event to the EV3
	 */
	public void getPose() {
		sendEvent(NavEvent.GET_POSE);
	}
	
	/**
	 * Send a GET_ESTIMATED_POSE event to the EV3
	 */
	public void getEstimatedPose() {
		sendEvent(NavEvent.GET_ESTIMATED_POSE);
	}
	
	/**
	 * Send a GET_READINGS event to the EV3
	 */
	public void getRemoteReadings() {
		sendEvent(NavEvent.GET_READINGS);
	}
	
	/**
	 * Send a SET_POSE event to the EV3
	 * 
	 * @param p the robot pose
	 */
	public void setPose(Pose p) {
		path = null;
		currentPose = p;
		// Record poses to plot the moves on the map. Reset when pose is set on 
		poses.clear();
		poses.add(new Pose(currentPose.getX(), currentPose.getY(), currentPose.getHeading()));
		if (mesh != null) {
			if (start != null) mesh.removeNode(this.start);
			start = new Node(p.getX(), p.getY());
			mesh.addNode(start, 4);
		}
		// Send a SET_POSE to the PC application
		panel.eventReceived(NavEvent.SET_POSE);
		panel.repaint();
		if (connected) {
			try {
				synchronized(receiver) {
					if (debug) panel.log("Sending SET_POSE");
					dos.writeByte(NavEvent.SET_POSE.ordinal());
					currentPose.dumpObject(dos);
				}
		    } catch (IOException ioe) {
				panel.error("IO Exception in getPose");
			}	
		}
	}
	
	/**
	 * Set the target for a path finder
	 */
	public void setTarget(Waypoint target) {
		path = null;
		this.target = target;
		if (mesh != null) {
			if(destination != null) mesh.removeNode(destination);
			destination = new Node((float) target.getX(), (float) target.getY());
			if (mesh != null) mesh.addNode(destination, 4);
		}
		panel.repaint();
	}
	
	/**
	 * Send a STOP event to the EV3
	 */
	public void stop() {
		sendEvent(NavEvent.STOP);
	}
	
	/**
	 * Send a RANDOM_MOVE event to the EV3
	 */
	public void randomMove() {
		sendEvent(NavEvent.RANDOM_MOVE);
	}
	
	/**
	 * Tell the EV3 to keep making random moves until the robot is localized
	 */
	public void localize() {
		sendEvent(NavEvent.LOCALIZE);
	}
	
	/**
	 * Send a TAKE_READINGS event to the EV3
	 */
	public void takeReadings() {
		sendEvent(NavEvent.TAKE_READINGS);
	}
	
	/**
	 * Send Random Move parameters to the EV3
	 */
	public void sendRandomMoveParams(float maxDistance, float clearance) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(NavEvent.RANDOM_MOVE_PARAMS.ordinal());
				dos.writeFloat(maxDistance);
				dos.writeFloat(clearance);
				dos.flush();
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in sendRandom");
		}
	}
	
	/**
	 * Send a route to the EV3 and follow it
	 */
	public void followPath() {
		if (path == null) return;
		if (!connected) return;
		try {
			if (debug) {
				panel.log("Sending path");
				panel.log("Pose:" + currentPose);
				for(Waypoint wp: path) {
					panel.log("Waypoint:" + wp.x + "," + wp.y + "," + wp.getHeading() + "," + wp.isHeadingRequired());
				}
			}
			synchronized(receiver) {
				dos.writeByte(NavEvent.FOLLOW_PATH.ordinal());
				path.dumpObject(dos);
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in followPath");
		}
	}
	
	/**
	 * Start the navigator following a path
	 */
	public void startNavigator() {
		sendEvent(NavEvent.START_NAVIGATOR);
	}
	
	/**
	 * Send a FIND_PATH event to the EV3
	 */
	public void findPath(Waypoint wp) {
		sendWaypoint(NavEvent.FIND_PATH, wp);
	}
	
	private void sendWaypoint(NavEvent navEvent, Waypoint wp) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(navEvent.ordinal());
				wp.dumpObject(dos);
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in " + navEvent.name());
	    }		
	}
	
	/**
	 * Send a CLEAR_PATH event to the EV3
	 */
	public void clearPath() {
		sendEvent(NavEvent.CLEAR_PATH);
	}
	
	/**
	 * Send an EXITevent to the EV3
	 * 
	 */
	public void sendExit() {
		sendEvent(NavEvent.EXIT);
	}
	
	/**
	 * Calculate the path with the Node path finder
	 */
	public void calculatePath() {
		if (currentPose == null || target == null) return;
		if (pf == null) return;
		try {
			path = pf.findRoute(currentPose, target);
			//if (debug) panel.log("Path = " + path);
			panel.repaint();
		} catch (DestinationUnreachableException e) {
			path = null;
			panel.error("Destination unreachable");
		}
	}
	
	/**
	 * Send a system sound
	 */
	public void sendSound(int code) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(NavEvent.SOUND.ordinal());
				dos.writeInt(code);
				dos.flush();
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in sendSound");
		}
	}
	
	/**
	 * Get the remote battery voltage
	 */
	public void getRemoteBattery() {
		sendEvent(NavEvent.GET_BATTERY);
	}
	
	private void sendEvent(NavEvent navEvent) {
		if (!connected) return;
		try {
			synchronized(receiver) {
				dos.writeByte(navEvent.ordinal());
				dos.flush();
			}
	    } catch (IOException ioe) {
			panel.error("IO Exception in " + navEvent.name());
	    }	
	}
		
	/**
	 * Shut down the receiver thread
	 */
	public void shutDown() {
		running = false;
	}
	
	/**
	 * Clear all variable data
	 */
	public void clear() {
		moves = new ArrayList<Move>();
		poses = new ArrayList<Pose>();
		features = new ArrayList<Point>();
		waypoints = new ArrayList<Waypoint>();
		target = null;
		path = null;
		start = null;
		destination = null;
		if (map != null){
			mesh = new FourWayGridMesh(map, gridSpace,clearance);
			nodes = mesh.getMesh();
			pf = new NodePathFinder(alg, mesh);
		}
		closest = -1;
	}
	
	/**
	 * Runnable class to receive events from the EV3
	 * 
	 * @author Lawrie Griffiths
	 *
	 */
	class Receiver implements Runnable {
		public void run() {
			while(running) {
				try {
					byte event = dis.readByte();
					NavEvent navEvent = NavEvent.values()[event];
					if (debug) panel.log("Event received:" +  navEvent.name());
					synchronized(this) {
						switch (navEvent) {
						case MOVE_STARTED: // Get planned move
							lastPlannedMove.loadObject(dis);
							if(debug) panel.log("Move Started: " + lastPlannedMove);
							break;
						case MOVE_STOPPED: // Get executed move
							lastMove = new Move(false, 0, 0);  // Dummy move
							lastMove.loadObject(dis);
							moves.add(lastMove);
							break;
						case SET_POSE: // Get a new pose from the EV3
							currentPose.loadObject(dis);
							if (debug) panel.log(currentPose.toString());
							poses.add(new Pose(currentPose.getX(), currentPose.getY(), currentPose.getHeading()));
							break;
						case PARTICLE_SET: // Get a particle set from the EV3
							particles.loadObject(dis);
							if (mcl != null) mcl.estimatePose();
							break;
						case RANGE_READINGS: // Get the range readings from the EV3
							readings.loadObject(dis);
							break;
						case WAYPOINT_REACHED: // Get the waypoint reached
							if (reached == null) reached = new Waypoint(0,0);
							reached.loadObject(dis);
							break;
						case CLOSEST_PARTICLE: // Get details of a specific particle
							closest = dis.readInt();
							//System.out.println("Closest: " + closest);
							particleReadings.loadObject(dis);
							weight = dis.readFloat();
							
							if (debug) {
								for(RangeReading r:particleReadings) {
									panel.log(r.getAngle() + ":" + r.getRange());
								}
							
								panel.log("weight = " + weight);
							}
							break;
						case ESTIMATED_POSE: // Get the MCL estimated pose data
							mcl.loadObject(dis);
							break;
						case FEATURE_DETECTED: // Get feature detected
							feature.loadObject(dis);
							float range = feature.getRangeReading().getRange();	
							Pose pose = feature.getPose();
							if (debug) panel.log("Pose = " + pose);
							if (debug) panel.log("Range = " + range);
							Point p = new Point((float) (pose.getX() + (range  * Math.cos(Math.toRadians(pose.getHeading())))),
												(float) (pose.getY() + (range  * Math.sin(Math.toRadians(pose.getHeading())))));
							if (debug) panel.log("Point = " + p);
							features.add(p);
							break;
						case PATH: // Get a path generated on the EV3
							path.loadObject(dis);
							break;
						case BATTERY:
							voltage = dis.readFloat();
							break;
						}
						// Signal that an event has been received
						panel.eventReceived(navEvent);
						// Refresh the navigation panel with the updated model data
						panel.repaint();
					}
				} catch (IOException ioe) {
					panel.fatal("IOException in receiver: " + ioe);
				}
			}		
		}	
	}
}
