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

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.Color;
import java.awt.Point;

@Plugin(type = Command.class, menuPath = "Plugins>ZF Tracking")
public class ZF_Tracking<T extends RealType<T>> implements Command, KeyListener, MouseListener {
    String TEXT1 = "Click on each of the tracked particles.\nPress [ _ ] (underscore) once done, then OK to resume.";
    String TEXT2 = "Choose a threshold value such that unwanted objects are selected.\nPress OK once done.";
    String TEXT3 = "Stitch the trajectories by clicking on 2 of them with the point selection tool.\nPress [ _ ] (underscore) at each 2 that have been selected. "
    		+ "\nThe 2 last clicked trajectories are bound together. Once done, press OK to resume.";
    double defaultRadius = 20.0, collisionHandlerRadius = 20.0, objectThreshold = 100, zfThreshold = 110, zfSize = 2, COLLISION_DIST = 8;
    int MAXIMUM_AREA = 50, startSlice, collision, nbClicks = 1, frameRate=30;
    boolean canCreateROIs = true, trackingDone = false;
    boolean[] canMove; // zf rois are immobilized while handling collisions
    RoiManager zfManager, objManager,collisionManager;
    ArrayList<Roi> collisionList = new ArrayList<Roi>();
    int[] collisionCounter;
    int[][] collisionRecord;
    Point[][] positionList;
    Roi[] roiArray, roiArrayStart;
    Roi collisionRoi;
    Point minPoint;
    ImageProcessor imp;
    ParticleAnalyzer pa;
    ResultsTable rt;
    Overlay trajectories;
    
    @Override
    public void run() {
    	// making the image responsive to hereby defined key inputs
    	IJ.getImage().getWindow().removeKeyListener(IJ.getInstance());
    	IJ.getImage().getWindow().getCanvas().removeKeyListener(IJ.getInstance());
    	IJ.getImage().getWindow().addKeyListener(this);
    	IJ.getImage().getWindow().getCanvas().addKeyListener(this);
    	IJ.getImage().getWindow().addMouseListener(this);
    	IJ.getImage().getWindow().getCanvas().addMouseListener(this);
        
    	// a ROI manager for tracking objects
    	zfManager = new RoiManager(false);
    	zfManager.runCommand("Show All with labels");
    	zfManager.runCommand("Associate", "true");
    	// a ROI manager to exclude large objects
    	objManager = new RoiManager(false); // uses a dedicated particle analyzer which stores in it all particles for which size > max
    	pa = new ParticleAnalyzer(ParticleAnalyzer.ADD_TO_MANAGER,0,rt,MAXIMUM_AREA,Double.MAX_VALUE);
    	ParticleAnalyzer.setRoiManager(objManager);
    	// a ROI manager for handling collisions
    	collisionManager = new RoiManager(false);
    	
    	// getting user inputs
        GenericDialog gd = new GenericDialog("ZF Larvae Tracking Parameters");
        gd.addNumericField("Threshold:", upperThreshold, 90);
        gd.addNumericField("Threshold:", zfThreshold, 80);
        gd.addNumericField("Zebra fish size:", zfSize, 2);
        gd.addNumericField("Frame rate:", frameRate, 30);
        gd.showDialog();
        if (gd.wasCanceled()) 
        	return;
        upperThreshold = gd.getNextNumber();
        zfThreshold = gd.getNextNumber();
        zfSize = gd.getNextNumber();
        frameRate = gd.getNextNumber();
        
    	IJ.setTool("multipoint");
    	new WaitForUserDialog(TEXT1).show(); 
    	int nbSlices = IJ.getImage().getImageStackSize(), nbROIs = roiArrayStart.length;
    	canMove = new boolean[nbROIs]; Arrays.fill(canMove, true);
    	collisionCounter = new int[nbROIs/2];
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

    	setRoisFromArray(roiArrayStart);
    	for (int i = startSlice ; i > 1 ; i--) {
    		moveRois(i,i-1);
    	}
    	
    	/* Draw the trajectories as an overlay of the input image 
    	 * When a collision is detected in the trajectory (the zf have been stalled),
    	 * the already read positions are written to a new polyline.
    	 */
    	trajectories = new Overlay();
    	for (int i = 0 ; i < nbROIs ; i++) {
    		ArrayList<Integer> x = new ArrayList<Integer>(), y = new ArrayList<Integer>(); 
    		for (int j = 1 ; j < nbSlices ; j++) {
    			if (collisionRecord[i][j] == 1) {
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
    	
    	IJ.getImage().setOverlay(trajectories);
    	trackingDone = true;
    	
    	// stitch the trajectories together
    	zfManager.reset(); // re-use the zfManager for handling the clicking
    	new WaitForUserDialog(TEXT3).show();
    	
    	// write the positions to a result table: zebrafish index, X, Y.
    	trackingDone = false;
    	IJ.run("Clear Results", "");
    	IJ.run("Set Measurements...", "  redirect=None decimal=4");
    	double speed;
    	for (int zf = 0; zf < nbROIs; zf++) {
    		for (int slice = 0; slice < nbSlices; slice++) {
    			rt.addRow();
    			rt.addValue("Zebrafish", zf+1);
    			rt.addValue("On slice", slice);
    			rt.addValue("X", positionList[zf][slice].x);
    			rt.addValue("Y", positionList[zf][slice].y);
    			speed = slice > 0 ? distance(positionList[zf][slice],positionList[zf][slice-1])/frameRate:0;
    			rt.addValue("Speed (pixels/s)", speed);
    		}
    	}
    	rt.show("Zebrafish Tracking Results");
    }

    void moveRois (int fromSlice, int toSlice) {
    	IJ.getImage().setPosition(toSlice);
    	objectThreshold = frameValue(objectThreshold, fromSlice, toSlice);
		IJ.setThreshold(0, objectThreshold, "No Update");
    	pa.analyze(IJ.getImage());
		
		roiArray = zfManager.getRoisAsArray();
    	for (int j = 0 ; j < roiArray.length ; j++) { // enhanced for loops don't allow updating the array within the loop (which reduce(args) is doing)
    		Roi roi = zfManager.getRoi(j);
    		Point center = roiCenter(roi);
    		positionList[j][toSlice-1] = center;
    		roi.setPosition(toSlice);
    		trackMin(j, fromSlice, toSlice);
    	}
        handleCollisions(fromSlice, toSlice);
    }

    void trackMin(int roiIndex, int fromSlice, int toSlice) {
	if (toSlice == positionList[0].length || toSlice < 0)
    		return;
    	Roi temp = zfManager.getRoi(roiIndex);
    	minPoint = getMin(temp);
    	if (isAvailable(minPoint) && canMove[roiIndex])
    		temp.setLocation(minPoint.x-temp.getBounds().width/2, minPoint.y-temp.getBounds().height/2);
    	collisionList = getCollisions(temp, roiIndex, fromSlice, toSlice);
    	if (collisionList.size() > 0)
    		reduce(temp, collisionList);
    	else {
    		expand(temp, roiIndex, fromSlice, toSlice);
    	}
    }

    Point getMin (Roi roi) {
    	imp = IJ.getImage().getStack().getProcessor(roi.getZPosition());
    	int min = 255, pixelValue = 0;
    	Point minPoint = new Point();
    	for (Point p:roi) {
    		pixelValue = (int) imp.getValue(p.x,p.y);
    		if (min > pixelValue) {
    			min = pixelValue;
    			minPoint = p;
    		}
    	}
    	return minPoint;
    }
    
    Point getMin (Roi roi, double threshold, ArrayList<Point> exceptThese, double exclusionRadius) {
    	if (exceptThese.size() == 0)
    		return getMin(roi);
    	
    	imp = IJ.getImage().getStack().getProcessor(roi.getZPosition());
    	int min = 255, pixelValue = 0, nbExcluded = exceptThese.size();
    	Point minPoint = new Point();
    	boolean available = true;
    	for (Point p:roi) {
    		pixelValue = (int) imp.getValue(p.x,p.y);
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
    	return min <= threshold ? minPoint : null;
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
    				collision = -(Double.compare(dist, COLLISION_DIST)>>1); // is equal to 1 when dist < COLLISION_DIST and 0 otherwise
    				collisionX += roiCenter(roi2).x*collision; 
    				collisionY += roiCenter(roi2).y*collision;
    				nbColliding += collision;
    				collisionRecord[roiIndex][toSlice] = collision;
    			}
    		}
    	}
    	// create a perimeter for handling the collision
    	collisionX += roiCenter(roi).x*collision; collisionY += roiCenter(roi).y*collision;
    	collisionX /= nbColliding; collisionY /= nbColliding;
    	if (collisionX + collisionY != 0 && !isInROI(new Point((int)collisionX,(int)collisionY),collisionManager)) {
			collisionRoi = makeCircleRoi(collisionX,collisionY,collisionHandlerRadius);
			collisionRoi.setPosition(toSlice);
			collisionManager.addRoi(collisionRoi);
			collisionRoi.setStrokeColor(Color.red);//DEBUG
			Overlay debug = new Overlay(collisionRoi);//DEBUG
			IJ.getImage().setOverlay(debug);//DEBUG
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
    void handleCollisions (int fromSlice, int toSlice) {
    	if (collisionManager.getCount() == 0)
    		return;
    	
    	double threshold = frameValue(zfThreshold,fromSlice,toSlice);
		// get the number of dark spots and the colliding zfs
		for (int c = 0; c < collisionManager.getCount(); c++) { // enhanced for loops cannot be used when there is no instance of the ROI manager
			ArrayList<Point> minima = new ArrayList<Point>();
			ArrayList<Integer> collidingROIs = new ArrayList<Integer>();
			Point min = new Point();
			collisionManager.getRoi(c).setPosition(toSlice);
			
			do {
				min = getMin(collisionManager.getRoi(c),threshold,minima,zfSize);
				if (min != null)
					minima.add(min);
			} while (min != null);
			
			for (int z = 0; z < zfManager.getCount(); z++) {
				if (distance(roiCenter(zfManager.getRoi(z)),roiCenter(collisionManager.getRoi(c))) < collisionHandlerRadius)
					collidingROIs.add(z);
			}
			
			if (minima.size() > collisionCounter[c] && collisionCounter[c] != 0) {
			// i.e if the number of dark spots is higher than on the previous frame and the previous frame and there was a previous frame of collision
				for (int p = 0; p<minima.size(); p++) {
					int index = collidingROIs.get(p);
					canMove[index] = true;
					collisionManager.select(c); collisionManager.runCommand(IJ.getImage(),"Delete");
					setCircleRoi(minima.get(p).x,minima.get(p).y,defaultRadius,index);
					collisionList = getCollisions(zfManager.getRoi(index), index, fromSlice, toSlice);
					if (collisionList.size()>0)
						reduce(zfManager.getRoi(index),collisionList);
				}
				collisionCounter[c] = 0;
			} else {
				for (int z:collidingROIs) {
					canMove[z] = false;
					positionList[z][toSlice] = null;
				}
				collisionCounter[c] = minima.size();
			}
		}
    }
    
    void reduce(Roi roi, ArrayList<Roi> collidesWith) {
    	for (Roi r:collidesWith) {
    		Point roi1 = roiCenter(roi), roi2 = roiCenter(r);
    		double radius = distance(roi1,roi2)/2;
    		setCircleRoi(roi1.x,roi1.y,radius,zfManager.getRoiIndex(roi));
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
    
    void createStartROIs() {
    	rt = ResultsTable.getResultsTable(); // returns the front-most rt
    	IJ.run("Clear Results", "");
    	IJ.run("Set Measurements...", "  redirect=None decimal=3");
    	IJ.run(IJ.getImage(), "Measure", "");
    	startSlice = (int) rt.getValue("Slice", 0);
    	double[][] coordinates = new double[2][rt.getCounter()];
    	for (int i = 0; i < coordinates[0].length*2; i++) {
    		coordinates[i%2][i/2] = i%2 == 0 ? rt.getValue("X", i/2) : rt.getValue("Y", i/2); // int a / int b = Math.floor(a/b);
    	}
    	IJ.run("Clear Results", "");
    	roiArrayStart = new Roi[coordinates[0].length];
    	for (int i = 0 ; i < coordinates[0].length ; i++) {
    		roiArrayStart[i] = makeCircleRoi(coordinates[0][i], coordinates[1][i], defaultRadius);
    	}
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
    	int roiIndex1 = 0; int roiIndex2 = 0, tempInt;
    	int endOfCollision=0, endOfCollision1=0, endOfCollision2=0, startOfCollision=0, startOfCollision1=0, startOfCollision2=0;
    	Point temp = new Point();
    	double minDist1 = Double.MAX_VALUE, minDist2 = Double.MAX_VALUE, dist;
    	
    	rt = ResultsTable.getResultsTable();
    	IJ.run("Clear Results","");
    	IJ.run("Set Measurements..."," redirect=None decimal=1");
    	IJ.run(IJ.getImage(), "Measure", "");
    	clicked1.x = (int) rt.getValue("X",0); clicked1.y = (int) rt.getValue("Y",0);
    	clicked2.x = (int) rt.getValue("X",1); clicked2.y = (int) rt.getValue("Y",1);

    	// find the nearest corresponding trajectory
    	for (int r = 0; r<positionList.length; r++) {
    		for (int s = 1; s<positionList[0].length; s++) {
    			endOfCollision = (collisionRecord[r][s]==0 && collisionRecord[r][s-1]==1)?s:endOfCollision;
    			startOfCollision = (collisionRecord[r][s]==1 && collisionRecord[r][s-1]==0)?s:startOfCollision;
    			dist = distance(clicked1,positionList[r][s]);
    			if (minDist1 > dist) {
    				minDist1 = dist;
    				roiIndex1 = r; endOfCollision1 = endOfCollision; startOfCollision1 = startOfCollision;
    			}
    			dist = distance(clicked2,positionList[r][s]);
    			if (minDist2 > dist) {
    				minDist2 = dist;
    				roiIndex2 = r; endOfCollision2 = endOfCollision; startOfCollision2 = startOfCollision;
    			}
    		}
    	}
    	
    	endOfCollision = endOfCollision2>endOfCollision1?endOfCollision2:endOfCollision1; // checking if the downstream trajectory is clicked2's.
    	startOfCollision = startOfCollision2>startOfCollision1?startOfCollision2:startOfCollision1;
    	// stitch the trajectories, i.e add points intermediary points when the zf was stalled
    	int s = startOfCollision, collisionDuration = endOfCollision - startOfCollision, step = 1;
    	do {
    		positionList[roiIndex1][s].x = ( positionList[roiIndex1][startOfCollision].x  * (collisionDuration - step) 
    				+ positionList[roiIndex2][endOfCollision].x * step ) / collisionDuration;
    		positionList[roiIndex1][s].y = ( positionList[roiIndex1][startOfCollision].y  * (collisionDuration - step) 
    				+ positionList[roiIndex2][endOfCollision].y * step ) / collisionDuration;
    		positionList[roiIndex2][s].x = ( positionList[roiIndex2][startOfCollision].x  * (collisionDuration - step) 
    				+ positionList[roiIndex1][endOfCollision].x * step ) / collisionDuration;
    		positionList[roiIndex2][s].y = ( positionList[roiIndex2][startOfCollision].y  * (collisionDuration - step)
    				+ positionList[roiIndex1][endOfCollision].y * step ) / collisionDuration;
    		step++;
    		s++;
    	} while ( (s < endOfCollision+1) && (
    			(collisionRecord[roiIndex1][s] == 1) || positionList[roiIndex1][s].equals(positionList[roiIndex1][s+1]) ));
    	// swap the points until then next collision is met, then continue swapping all equal points (stalled zf)
    	do {
    		temp = positionList[roiIndex1][s];
    		positionList[roiIndex1][s] = positionList[roiIndex2][s];
    		positionList[roiIndex2][s] = temp;
    		tempInt = collisionRecord[roiIndex1][s];
    		collisionRecord[roiIndex1][s] = collisionRecord[roiIndex2][s];
    		collisionRecord[roiIndex2][s] = tempInt;
    		s++;
    	} while ( (s < positionList[0].length-1) && (
    			(collisionRecord[roiIndex1][s] == 0 || positionList[roiIndex1][s].equals(positionList[roiIndex1][s+1]) ) &&
    			(collisionRecord[roiIndex2][s] == 0 || positionList[roiIndex2][s].equals(positionList[roiIndex2][s+1]))) );
    	// i.e until the next collision of one of the 2 downstream trajectories is done
    	int[] x1 = new int[positionList[0].length], x2 =  new int[positionList[0].length];
    	int[] y1 = new int[positionList[0].length], y2 =  new int[positionList[0].length];
    	for (int p = 0; p < positionList[0].length; p++) {
    		x1[p] = positionList[roiIndex1][p].x; x2[p] = positionList[roiIndex2][p].x;
    		y1[p] = positionList[roiIndex1][p].y; y2[p] = positionList[roiIndex2][p].y;
    	}
    	PolygonRoi polyline1 = new PolygonRoi(x1,y1,x1.length,PolygonRoi.POLYLINE);
    	PolygonRoi polyline2 = new PolygonRoi(x2,y2,x2.length,PolygonRoi.POLYLINE);
    	polyline1.setStrokeColor(new Color((float) Math.random(),(float) Math.random(),(float) Math.random()));
		polyline1.setStrokeWidth(1);
		polyline2.setStrokeColor(new Color((float) Math.random(),(float) Math.random(),(float) Math.random()));
		polyline2.setStrokeWidth(1);
		trajectories.add(polyline1); trajectories.add(polyline2);
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
    double frameValue(double value, int fromSlice, int toSlice) {
    	imp = IJ.getImage().getStack().getProcessor(fromSlice);
    	ImageProcessor imp2 = IJ.getImage().getStack().getProcessor(toSlice);
    	return value + imp2.getMax()-imp.getMax();
    }
    
    double distance(Point A, Point B) {
    	return Math.sqrt((A.x-B.x)*(A.x-B.x)+(A.y-B.y)*(A.y-B.y));
    }
    
    double[] getContainedPointsValues(Roi roi, int slice) {
    	Point[] containedPoints = roi.getContainedPoints();
    	double[] values = new double[containedPoints.length];
    	for (int i = 0 ; i < containedPoints.length ; i++) {
    		values[i] = IJ.getImage().getStack().getProcessor(slice).getValue(containedPoints[i].x,containedPoints[i].y);
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
    		IJ.run(IJ.getImage(), "Select None", "");
    		nbClicks = 0;
    	}
		nbClicks += (e.getButton() == MouseEvent.BUTTON1 && e.getID() == MouseEvent.MOUSE_PRESSED && trackingDone)?1:0;
	}

    public static void main(final String... args) throws Exception {
    	@SuppressWarnings("unused")
		ImageJ ij = new ImageJ();
    	ZF_Tracking<FloatType> zf = new ZF_Tracking<FloatType>();
    	IJ.run("AVI...", "avi=/home/criuser/Desktop/Licence/Stage_L3/n=15-06252023180457-0000.avi use convert first=500 last=537");
    	// invoke the plugin
        zf.run();
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
