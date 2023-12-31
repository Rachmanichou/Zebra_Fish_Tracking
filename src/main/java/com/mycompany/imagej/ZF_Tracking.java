package com.mycompany.imagej;

import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

import ij.gui.WaitForUserDialog;
import ij.gui.Roi;
import ij.gui.PolygonRoi;
import ij.gui.GenericDialog;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.plugin.frame.RoiManager;
import ij.plugin.filter.ParticleAnalyzer;
import ij.process.ImageProcessor;
import ij.measure.ResultsTable;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.Color;
import java.awt.Point;

@Plugin(type = Command.class, menuPath = "Plugins>ZF Tracking")
public class ZF_Tracking<T extends RealType<T>> implements Command, KeyListener, MouseListener {
    String TEXT1 = "Click on each of the tracked particles.\nPress [ _ ] (underscore) once done, then OK to resume.";
    String TEXT2 = "Stitch the trajectories by clicking on 2 of them with the point selection tool.\nPress [ _ ] (underscore) at each 2 that have been selected. "
    		+ "\nThe 2 last clicked trajectories are bound together. Once done, press OK to resume.";
    double defaultRadius = 20.0, collisionHandlerRadius = 20.0, objectThreshold = 100, zfThreshold = 125, zfSize = 2, collision_dist = 5, previousSliceMax, zfThreshApprox = 1;
    int maximal_area = 80, startSlice, collision, nbClicks = 1, frameRate=30;
    boolean canCreateROIs = true, trackingDone = false;
    boolean[] canMove; // zf rois are immobilized while handling collisions
    RoiManager zfManager, objManager,collisionManager;
    ArrayList<Roi> collisionList = new ArrayList<Roi>();
    int[][] collisionRecord;
    Point[][] positionList;
    Roi[] roiArray, roiArrayStart;
    Roi collisionRoi;
    Point minPoint;
    ImageProcessor imp;
    ImagePlus image;
    ParticleAnalyzer pa;
    ResultsTable rt;
    
    @Override
    public void run() {
    	image = IJ.getImage();
    	// making the image responsive to hereby defined key inputs
    	image.getWindow().addKeyListener(this);
    	image.getWindow().getCanvas().addKeyListener(this);
    	image.getWindow().addMouseListener(this);
    	image.getWindow().getCanvas().addMouseListener(this);
        
    	// a ROI manager for tracking objects
    	zfManager = new RoiManager(false);
    	zfManager.runCommand("Show All with labels");
    	zfManager.runCommand("Associate", "true");
    	// a ROI manager to exclude large objects
    	objManager = new RoiManager(false); // uses a dedicated particle analyzer which stores in it all particles for which size > max
    	pa = new ParticleAnalyzer(ParticleAnalyzer.ADD_TO_MANAGER,0,rt,maximal_area,Double.MAX_VALUE);
    	ParticleAnalyzer.setRoiManager(objManager);
    	// a ROI manager for handling collisions
    	collisionManager = new RoiManager(false);
    	
    	// getting user inputs. returns a boolean for canceling the macro and stores the input values
    	if (getUserInputs())
    		return;
        
    	IJ.setTool("multipoint");
    	new WaitForUserDialog(TEXT1).show(); 
    	int nbSlices = image.getImageStackSize(), nbROIs = roiArrayStart.length;
    	canMove = new boolean[nbROIs]; Arrays.fill(canMove, true);
    	collisionRecord = new int[nbROIs][nbSlices];
        
    	roiArray = roiArrayStart;
    	positionList = new Point[nbROIs][nbSlices];
    	minPoint = new Point();
    	// base case
    	for (int i = 0 ; i < roiArray.length ; i++) {
    		zfManager.addRoi(roiArray[i]);
    		positionList[i][startSlice-1] = roiCenter(roiArray[i]);
    	}
    	
    	// compute trajectories
    	for (int i = startSlice ; i < nbSlices ; i++) {
    		moveRois(i,i+1);
    	}

    	setRoisFromArray(roiArrayStart); // re-initialize the zfManager
    	Arrays.fill(canMove, true); // re-initialize collision handling
    	collisionManager.reset();
    	
    	for (int i = startSlice ; i > 1 ; i--) {
    		moveRois(i,i-1);
    	}
    	
    	/* Draw the trajectories as an overlay of the input image 
    	 * When a collision is detected in the trajectory,
    	 * the already read positions are written to a new polyline.
    	 */
    	image.setOverlay(zfTrajectories(false));
    	trackingDone = true;
    	
    	// stitch the trajectories together
    	zfManager.reset(); // re-use the zfManager for handling the clicking
    	new WaitForUserDialog(TEXT2).show();
    	
    	// write the positions to a result table: zebrafish index, X, Y, speed.
    	trackingDone = false;
    	IJ.run("Clear Results", "");
    	IJ.run("Set Measurements...", "  redirect=None decimal=4");
    	double speed;
    	for (int zf = 0; zf < nbROIs; zf++) {
    		for (int slice = 0; slice < nbSlices; slice++) {
    			rt.addRow();
    			rt.addValue("Zebrafish", zf+1);
    			rt.addValue("On slice", slice+1);
    			rt.addValue("X", positionList[zf][slice].x);
    			rt.addValue("Y", positionList[zf][slice].y);
    			speed = slice > 0 ? distance(positionList[zf][slice],positionList[zf][slice-1])/frameRate:0;
    			rt.addValue("Speed (pixels/s)", speed);
    			rt.addValue("Collision", collisionRecord[zf][slice]);
    		}
    	}
    	rt.show("Zebrafish Tracking Results");
    }

    void moveRois (int fromSlice, int toSlice) {
    	double flickeringVariation = sliceDiff(fromSlice, toSlice);
    	image.setPosition(toSlice);
    	objectThreshold += flickeringVariation;
    	zfThreshold += flickeringVariation;
		IJ.setThreshold(0, objectThreshold, "No Update");
    	pa.analyze(image);
		
		roiArray = zfManager.getRoisAsArray();
		ImageProcessor nextSlice = image.getStack().getProcessor(toSlice);
    	for (int j = 0 ; j < roiArray.length ; j++) { // enhanced for loops don't allow updating the array within the loop (which reduce(args) is doing)
    		Roi roi = zfManager.getRoi(j);
    		Point center = roiCenter(roi);
    		positionList[j][toSlice-1] = center;
    		roi.setPosition(toSlice);
    		trackMin(j, fromSlice, toSlice, nextSlice);
    	}

        handleCollisions(fromSlice, toSlice, nextSlice);
    }

    void trackMin(int roiIndex, int fromSlice, int toSlice, ImageProcessor ip) {
    	if (toSlice == positionList[0].length || toSlice < 0)
    		return;
    	Roi temp = zfManager.getRoi(roiIndex);
    	minPoint = getMin(temp,ip);
    	if (isAvailable(minPoint) && canMove[roiIndex])
    		temp.setLocation(minPoint.x-temp.getBounds().width/2, minPoint.y-temp.getBounds().height/2);
    	collisionList = getCollisions(temp, roiIndex, fromSlice, toSlice);
    	if (collisionList.size() > 0)
    		reduce(temp, collisionList);
    	else {
    		expand(temp, roiIndex, fromSlice, toSlice);
    	}
    }

    Point getMin (Roi roi, ImageProcessor ip) {
    	int min = 255, pixelValue = 0;
    	Point minPoint = new Point();
    	for (Point p:roi) {
    		pixelValue = (int) ip.getValue(p.x,p.y);
    		if (min > pixelValue) {
    			min = pixelValue;
    			minPoint = p;
    		}
    	}
    	return minPoint;
    }
    
    Point getMin (Roi roi, ImageProcessor ip, double threshold) {
    	int min = 255, pixelValue = 0;
    	Point minPoint = new Point();
    	for (Point p:roi) {
    		pixelValue = (int) ip.getValue(p.x,p.y);
    		if (min > pixelValue) {
    			min = pixelValue;
    			minPoint = p;
    		}
    	}
    	return (min <= threshold+zfThreshApprox) ? minPoint : null;
    }
    
    Point getMin (Roi roi, ImageProcessor ip, double threshold, ArrayList<Point> exceptThese, double exclusionRadius) {
    	if (exceptThese.size() == 0)
    		return getMin(roi, ip, threshold);
    	
    	int min = 255, pixelValue = 0, nbExcluded = exceptThese.size();
    	Point minPoint = new Point();
    	boolean available = true;
    	for (Point p:roi) {
    		pixelValue = (int) ip.getValue(p.x,p.y);
    		int p1 = 0;
    		do {
    			available = distance(exceptThese.get(p1),p)>exclusionRadius;
    			p1++;
    		} while (available && p1 < nbExcluded);
    		
    		if (min > pixelValue && available) {
    			min = pixelValue;
    			minPoint = p;
    		}
    		available = true;
    	}
    	return (min <= threshold+zfThreshApprox) ? minPoint : null;
    }

    boolean isAvailable(Point point) {
    	boolean isRoiCenter = false;
    	for (int r = 0; r<zfManager.getCount(); r++) {
    		isRoiCenter = point.equals(roiCenter(zfManager.getRoi(r)));
    	}
    	return !(isInROI(point,objManager) || isRoiCenter);
    }

    ArrayList<Roi> getCollisions(Roi roi, int roiIndex, int fromSlice, int toSlice) { // collisions occurring on toSlice
    	ArrayList<Roi> collidesWith = new ArrayList<Roi>();
    	double collisionX = 0, collisionY = 0;
    	int nbColliding = 1;
    	for (int i = 0; i < zfManager.getCount(); i++) {
    		if (roiIndex != i) {
    			Roi roi2 = zfManager.getRoi(i);
    			double minDist = (roi.getBounds().getWidth() + roi2.getBounds().getWidth())/2, dist = distance(roiCenter(roi),roiCenter(roi2));
    			if (dist <= minDist) { // the bounding boxes are colliding
    				collidesWith.add(roi2);
    				collision = -(Double.compare(dist, collision_dist)>>1); // is equal to 1 when dist < COLLISION_DIST and 0 otherwise
    				collisionX += roiCenter(roi2).x*collision; 
    				collisionY += roiCenter(roi2).y*collision;
    				nbColliding += collision;
    				collisionRecord[roiIndex][toSlice] = collision;
    			}
    		}
    	}
    	// create a perimeter for handling the collision
    	collisionX += collisionX > 0 ? roiCenter(roi).x : 0; collisionY += collisionY > 0 ? roiCenter(roi).y : 0;
    	collisionX /= nbColliding; collisionY /= nbColliding;
    	if (collisionX + collisionY != 0 && !isInROI(new Point((int)collisionX,(int)collisionY),collisionManager)) {
			collisionRoi = makeCircleRoi(collisionX,collisionY,collisionHandlerRadius);
			collisionRoi.setPosition(toSlice);
			collisionManager.addRoi(collisionRoi);
    	}
    	collision = 0;
    	
        return collidesWith;
    }
    
    /* Called once per frame. Doesn't match trajectories after collision. Stitching is done manually or by a separate method (?).
     * Detects the zf in the collision area and draws a new trajectory for the zf moving away from the collision.
     * 1.  Counts the number of zf in each of the colliding areas thanks to thresholding methods.
     * 2.a If the number increases (i.e the dots are separating), assign the ROIs to minima without preference and allow their movement. 
     * 2.b Else, replace the number with the current value and write down <code>null<\code> as the position of the ROI on this frame and prohibit their movement.
     *     This will then be used when stitching the trajectories together.
     */
    void handleCollisions (int fromSlice, int toSlice, ImageProcessor nextSlice) {
    	if (collisionManager.getCount() == 0)
    		return;

		// get the number of colliding zfs, nearby zfs which might interfere, and dark spots
		for (int c = 0; c < collisionManager.getCount(); c++) { // enhanced for loops cannot be used when there is no instance of the ROI manager
			ArrayList<Point> minima = new ArrayList<Point>();
			ArrayList<Integer> collidingZFs = new ArrayList<Integer>(), inRadiusZFs = new ArrayList<Integer>(); 
			ArrayList<Point> nearbyZFs = new ArrayList<Point>();
			Point min = new Point();
			double dist = 0;
			boolean isColliding = false;
			collisionManager.getRoi(c).setPosition(toSlice);
			
			// this lengthy approach has the advantage of selecting colliding ROIs based on their distances between one another, and not with the collision's centroid 
			for (int z = 0; z < zfManager.getCount(); z++) {
				dist = distance(roiCenter(zfManager.getRoi(z)),roiCenter(collisionManager.getRoi(c)));
				if (dist < collisionHandlerRadius) {
					inRadiusZFs.add(z);
				}
			}
			for (int z:inRadiusZFs) {
				int z1 = 0;
				Point z_center = roiCenter(zfManager.getRoi(z));
				do {
					dist = (z != z1) ? distance(z_center, roiCenter(zfManager.getRoi( inRadiusZFs.get(z1) ))) : Double.MAX_VALUE;
					isColliding = dist <= collision_dist;
					z1++;
				} while (!isColliding && z1 < inRadiusZFs.size());
				
				if (isColliding)
					collidingZFs.add(z);
				else {
					nearbyZFs.add(z_center);
				}
				
				isColliding = false;
			} // i.e ROI managers that are in the collision manager but not partaking in the collision are stored in nearbyZFs and excluded from minima search below
			
			do {
				// exclude from the search minima that are already counted in and minima that belong to nearby ROIs which are not colliding
				min = getMin(collisionManager.getRoi(c),nextSlice,zfThreshold,nearbyZFs,zfSize);
				if (min != null) {
					minima.add(min);
					nearbyZFs.add(min);
				}
			} while (min != null);
			
			int nbDarkSpots = minima.size();
			for (int z:collidingZFs) {
				canMove[z] = nbDarkSpots == 0;
			}
			if (nbDarkSpots <= collidingZFs.size() && nbDarkSpots > 1) {
			// i.e at least one zf is moving away from the collision
				for (int p = 0; p<nbDarkSpots; p++) {
					int index = collidingZFs.get(p);
					canMove[index] = true;
					setCircleRoi(minima.get(p).x,minima.get(p).y,defaultRadius,index);
					collisionList = getCollisions(zfManager.getRoi(index), index, fromSlice, toSlice);
					if (collisionList.size()>0)
						reduce(zfManager.getRoi(index),collisionList);
				}
				if (nbDarkSpots == collidingZFs.size()) {
					collisionManager.select(c); 
					collisionManager.runCommand(image,"Delete");
				}
			}
		}
    }
    
    void reduce(Roi roi, ArrayList<Roi> collidesWith) {
    	int index = zfManager.getRoiIndex(roi);
    	for (Roi r:collidesWith) {
    		Roi reduce = zfManager.getRoi(index);
    		Point roi1 = roiCenter(reduce), roi2 = roiCenter(r);
    		double radius = distance(roi1,roi2); // since the roi will be constructed from the top left corners of their bounding boxes...
    		setCircleRoi(roi1.x,roi1.y,radius,zfManager.getRoiIndex(reduce));
    		setCircleRoi(roi2.x,roi2.y,radius,zfManager.getRoiIndex(r)); 
    		roiArray = zfManager.getRoisAsArray(); // After running rm.setRoi(), r and roi refer to nothing anymore.
    	}
    }

    void expand(Roi roi, int roiIndex, int fromSlice, int toSlice) {
    	double x = roiCenter(roi).x, y = roiCenter(roi).y;
    	setCircleRoi(x,y,defaultRadius,zfManager.getRoiIndex(roi));
    	roi = zfManager.getRoi(roiIndex); // update the reference to the newly expanded roi before checking for collisions
    	collisionList = getCollisions(roi, roiIndex, fromSlice, toSlice);
    	if (collisionList.size() > 0)
    		reduce(roi, collisionList);	
    }
    
    // ImageJ constructs rois based on a the top-left point of the bounding rectangle.
    // This method allows the use of center points.
    void setCircleRoi(double x, double y, double radius, int index) {
    	zfManager.setRoi(makeCircleRoi(x,y,radius), index);
    }
    
    Roi makeCircleRoi(double x, double y, double radius) {
    	return new OvalRoi(x-radius/2,y-radius/2,radius,radius);
    }
    
    // getX,YBase returns the coordinates of the top left corner of the bounding rectangle.
    // gets the center of the ROI.
    Point roiCenter(Roi roi) {
    	double width = roi.getBounds().getWidth();
    	double height = roi.getBounds().getHeight();
    	double x = roi.getXBase() + width/2;
    	double y = roi.getYBase() + height/2;
    	return new Point((int) x,(int) y);
    }
    
    // create the search ROIs from user inputs and define a pixel value threshold for zebrafish (i.e the max of all mins from the startROIs)
    void createStartROIs() {
    	ArrayList<Double> minList = new ArrayList<Double>();
    	rt = ResultsTable.getResultsTable(); // returns the front-most rt
    	IJ.run("Clear Results", "");
    	IJ.run("Set Measurements...", "  redirect=None decimal=3");
    	IJ.run(image, "Measure", "");
    	startSlice = (int) rt.getValue("Slice", 0);
    	double[][] coordinates = new double[2][rt.getCounter()];
    	for (int i = 0; i < coordinates[0].length*2; i++) {
    		coordinates[i%2][i/2] = i%2 == 0 ? rt.getValue("X", i/2) : rt.getValue("Y", i/2); // int a / int b = Math.floor(a/b);
    	}
    	IJ.run("Clear Results", "");
    	roiArrayStart = new Roi[coordinates[0].length];
    	ImageProcessor ipStart = IJ.getImage().getStack().getProcessor(startSlice);
    	for (int i = 0 ; i < coordinates[0].length ; i++) {
    		roiArrayStart[i] = makeCircleRoi(coordinates[0][i], coordinates[1][i], defaultRadius);
    		minList.add(Collections.min( Arrays.asList(getContainedPointsValues(roiArrayStart[i],ipStart))) );
    	}
    	zfThreshold = Collections.max(minList);
    }
    
    /* When the user clicks on the image, the nearest point in positionList is collected.
	 * If the user presses [ _ ], the 2 corresponding paths are stitched together:
	 * Starting from the nearest upstream registered collision and finishing at the nearest downstream registered collision of the second clicked roi,
	 * all the points of this roi are swapped with the points of the first clicked at the corresponding slices. The collision record also has to be swapped.
	 * The points that were registered during the collision are replaced by equi-distant points along a line from the last registered point 
	 * before the collision and the first after.
	 * These points are written to a new polyline which is added to the trajectory overlay.
	 * When the user presses [OK], the result computations are done.
	 * */
    void stitch() {
    	Point clicked1 = new Point(); Point clicked2 = new Point();
    	int roiIndex1 = 0; int roiIndex2 = 0, tempInt, nbSlices = positionList[0].length;
    	int endOfCollision=0, endOfCollision1=0, endOfCollision2=0, startOfCollision=0, startOfCollision1=0, startOfCollision2=0;
    	Point temp = new Point();
    	double minDist1 = Double.MAX_VALUE, minDist2 = Double.MAX_VALUE, dist;
    	
    	rt = ResultsTable.getResultsTable();
    	IJ.run("Clear Results","");
    	IJ.run("Set Measurements..."," redirect=None decimal=1");
    	IJ.run(image, "Measure", "");
    	clicked1.x = (int) rt.getValue("X",0); clicked1.y = (int) rt.getValue("Y",0);
    	clicked2.x = (int) rt.getValue("X",1); clicked2.y = (int) rt.getValue("Y",1);

    	// find the nearest corresponding trajectory
    	for (int r = 0; r<positionList.length; r++) {
    		for (int s = 1; s<nbSlices; s++) {
    			endOfCollision = (collisionRecord[r][s]==0 && collisionRecord[r][s-1]==1)?s:endOfCollision;
    			startOfCollision = (collisionRecord[r][s]==1 && collisionRecord[r][s-1]==0)?s:startOfCollision;
    			dist = distance(clicked1,positionList[r][s]);
    			if (minDist1 > dist) {
    				minDist1 = dist;
    				roiIndex1 = r; endOfCollision1 = endOfCollision+1; startOfCollision1 = startOfCollision+1;
    			}
    			dist = distance(clicked2,positionList[r][s]);
    			if (minDist2 > dist) {
    				minDist2 = dist;
    				roiIndex2 = r; endOfCollision2 = endOfCollision+1; startOfCollision2 = startOfCollision+1;
    			}
    		}
    	}
    	
    	boolean traj1Upstream = endOfCollision2 > endOfCollision1;
    	endOfCollision = traj1Upstream ? endOfCollision2 : endOfCollision1; // checking if the downstream trajectory is clicked2's.
    	startOfCollision = traj1Upstream ? startOfCollision2 : startOfCollision1;

    	// stitch the trajectories, i.e add points intermediary points when the zf was stalled
    	int s = startOfCollision, collisionDuration = endOfCollision - startOfCollision, step = 1;
    	do {
    		if (traj1Upstream && collisionDuration != 0) {
        		positionList[roiIndex1][s].x = ( positionList[roiIndex1][startOfCollision].x  * (collisionDuration - step) 
        				+ positionList[roiIndex2][endOfCollision].x * step ) / collisionDuration;
        		positionList[roiIndex1][s].y = ( positionList[roiIndex1][startOfCollision].y  * (collisionDuration - step) 
        				+ positionList[roiIndex2][endOfCollision].y * step ) / collisionDuration;
    		} else if (collisionDuration != 0) {
        		positionList[roiIndex2][s].x = ( positionList[roiIndex2][startOfCollision].x  * (collisionDuration - step) 
        				+ positionList[roiIndex1][endOfCollision].x * step ) / collisionDuration;
        		positionList[roiIndex2][s].y = ( positionList[roiIndex2][startOfCollision].y  * (collisionDuration - step)
        				+ positionList[roiIndex1][endOfCollision].y * step ) / collisionDuration;
    		}
    		step++;
    		s++;
    	} while ( (s < endOfCollision) && (
    			(collisionRecord[roiIndex1][s] == 1) || positionList[roiIndex1][s].equals(positionList[roiIndex1][s+1]) ));
    	
    	// swap the points until then next collision is met, then continue swapping all equal points (stalled zf)
    	do {
    		if (traj1Upstream) {
    			temp = positionList[roiIndex1][s];
    			tempInt = collisionRecord[roiIndex1][s];
    			positionList[roiIndex1][s] = positionList[roiIndex2][s];
    			positionList[roiIndex2][s] = temp;
        		collisionRecord[roiIndex1][s] = collisionRecord[roiIndex2][s];
        		collisionRecord[roiIndex2][s] = tempInt;
    		}
    		else {
    			temp = positionList[roiIndex2][s];
    			tempInt = collisionRecord[roiIndex2][s];
    			positionList[roiIndex2][s] = positionList[roiIndex1][s];
    			positionList[roiIndex1][s] = temp;
        		collisionRecord[roiIndex2][s] = collisionRecord[roiIndex1][s];
        		collisionRecord[roiIndex1][s] = tempInt;
    		}
    		s++;
    	} while ( (s < nbSlices-1) && (
    			(collisionRecord[roiIndex1][s] == 0 || positionList[roiIndex1][s].equals(positionList[roiIndex1][s+1]) ) ||
    			(collisionRecord[roiIndex2][s] == 0 || positionList[roiIndex2][s].equals(positionList[roiIndex2][s+1]))) );
    	// i.e until the next collision of one of the 2 downstream trajectories is done
    	if (s == nbSlices-1) {
	    	temp = positionList[roiIndex1][s];
			positionList[roiIndex1][s] = positionList[roiIndex2][s];
			positionList[roiIndex2][s] = temp;
			tempInt = collisionRecord[roiIndex1][nbSlices-1];
			collisionRecord[roiIndex1][s] = collisionRecord[roiIndex2][s];
			collisionRecord[roiIndex2][s] = tempInt;
    	}
		
    	image.setOverlay(zfTrajectories(true));
    }
    
    void setRoisFromArray(Roi[] roiArray) {
    	if (roiArray.length != zfManager.getCount())
    		return;
    	int index = 0;
    	for (Roi roi:zfManager.getRoisAsArray()) {
    		index = zfManager.getRoiIndex(roi);
    		zfManager.setRoi(roiArray[index], index);
    	}
    }
    
    // adapts the given value to a slice's light exposure provided that no bright objects are introduced and that the change in light exposure is homogeneous.
    double sliceDiff(int fromSlice, int toSlice) {
    	if (fromSlice == startSlice)
    		previousSliceMax = maxValue( image.getStack().getProcessor(fromSlice) );
    	double toSliceMax = maxValue( image.getStack().getProcessor(toSlice) );
    	double diff = toSliceMax-previousSliceMax;
    	previousSliceMax = toSliceMax;
    	return diff;
    }
    
    double distance(Point A, Point B) {
    	return Math.sqrt((A.x-B.x)*(A.x-B.x)+(A.y-B.y)*(A.y-B.y));
    }
    
    Double[] getContainedPointsValues(Roi roi, ImageProcessor ip) {
    	Point[] containedPoints = roi.getContainedPoints();
    	Double[] values = new Double[containedPoints.length];
    	for (int i = 0 ; i < containedPoints.length ; i++) {
    		values[i] = ip.getValue(containedPoints[i].x,containedPoints[i].y);
    	}
    	return values;
    }
    
    boolean isInROI(Point p, RoiManager rm) {
    	for (int r = 0; r<rm.getCount(); r++) {
    		if (Arrays.asList(rm.getRoi(r).getContainedPoints()).contains(p))
    			return true;
    	}
    	return false;
    }

	@Override
	public void keyPressed(KeyEvent e) {
		if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_UNDERSCORE && canCreateROIs) {
			createStartROIs();
			canCreateROIs = false;
		}
		if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_UNDERSCORE && trackingDone)
			stitch();
	}
	@Override
	public void mousePressed (MouseEvent e) {
		if (nbClicks > 2) {
    		IJ.run(image, "Select None", "");
    		nbClicks = 0;
    	}
		nbClicks += (e.getButton() == MouseEvent.BUTTON1 && e.getID() == MouseEvent.MOUSE_PRESSED && trackingDone)?1:0;
	}
	
	boolean getUserInputs() {
		// get numeric fields
        	GenericDialog gd = new GenericDialog("ZF Larvae Tracking Numeric Parameters");
        	gd.addNumericField("Object maximal intensity", objectThreshold, 0);
       		gd.addNumericField("Object minimal size", maximal_area);
		gd.addNumericField("Error bar for zebrafish maximum intensity", zfThreshApprox);
       		gd.addNumericField("Frame rate", frameRate, 0);
		gd.addNumericField("Zebrafish_size", zfSize,0);
		gd.addNumericField("Tracking_radius", defaultRadius,0);
		gd.addNumericField("Collision_manager_radius", collisionHandlerRadius,0);
		gd.addNumericField("Collision_distance", collision_dist);
		gd.addHelp("https://github.com/Rachmanichou/Zebra_Fish_Tracking");
		gd.showDialog();
	
	        if (gd.wasCanceled()) 
	        	return true;
	        
	        objectThreshold = gd.getNextNumber();
	        maximal_area = (int) gd.getNextNumber();
	        zfThreshApprox = gd.getNextNumber();
	        frameRate = (int)gd.getNextNumber();
		zfSize = gd.getNextNumber();
	        defaultRadius = gd.getNextNumber();
	        collisionHandlerRadius = gd.getNextNumber();
	        collision_dist = gd.getNextNumber();
	        
	        return false;
	}
	
	Overlay zfTrajectories (boolean stitch) {
    	Overlay trajectories = new Overlay();
    	int nbROIs = positionList.length, nbSlices = positionList[0].length;
    	for (int i = 0 ; i < nbROIs ; i++) {
    		ArrayList<Integer> x = new ArrayList<Integer>(), y = new ArrayList<Integer>(); 
    		for (int j = 1 ; j < nbSlices ; j++) {
    			if (collisionRecord[i][j] == 1 && !stitch) {
    				int[] xArray = new int[x.size()], yArray = new int[y.size()];
    				for (int k = 0; k<x.size(); k++) {
    					xArray[k] = x.get(k); yArray[k] = y.get(k);
    				}
    				PolygonRoi polyline = new PolygonRoi(xArray,yArray,x.size(),PolygonRoi.POLYLINE);
    				polyline.setStrokeColor(new Color((float) Math.random(),(float) Math.random(),(float) Math.random()));
    				polyline.setStrokeWidth(1);
    				trajectories.add(polyline);
    				x = new ArrayList<Integer>(); y = new ArrayList<Integer>();
    			}
    			x.add(positionList[i][j].x); y.add(positionList[i][j].y);
    		}
    		int[] xArray = new int[x.size()], yArray = new int[y.size()];
    		for (int k = 0; k<x.size(); k++) {
    			xArray[k] = x.get(k); yArray[k] = y.get(k);
    		}
    		PolygonRoi polyline = new PolygonRoi(xArray,yArray,x.size(),PolygonRoi.POLYLINE);
    		polyline.setStrokeColor(new Color((float) Math.random(),(float) Math.random(),(float) Math.random()));
    		polyline.setStrokeWidth(1);
    		trajectories.add(polyline);
    	}
    	return trajectories;
	}
	
	double maxValue (ImageProcessor ip) {
		int w = ip.getWidth(), h = ip.getHeight(), max = 0;
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				if (max < ip.get(x,y))
					max = ip.get(x,y);
			}
		}
		return max;
	}

	@Override
	public void keyTyped(KeyEvent e) {}
	@Override
	public void keyReleased(KeyEvent e) {}
	@Override
	public void mouseClicked(MouseEvent e) {}
	@Override
	public void mouseReleased(MouseEvent e) {}
	@Override
	public void mouseEntered(MouseEvent e) {}
	@Override
	public void mouseExited(MouseEvent e) {}
}
