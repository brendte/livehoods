package com.brendteneickstaedt.finalproject;

import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import com.mongodb.Mongo;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.DBCursor;
import com.mongodb.MongoException;

import cern.colt.matrix.DoubleMatrix2D;
import cern.colt.matrix.impl.SparseDoubleMatrix1D;
import cern.colt.matrix.impl.SparseDoubleMatrix2D;
import cern.colt.matrix.linalg.Algebra;
import cern.colt.matrix.linalg.EigenvalueDecomposition;

public class Cluster {

	public static double cosineSimularity(SparseDoubleMatrix1D c_i,
			SparseDoubleMatrix1D c_j) {
		if (c_i.cardinality() != 0 && c_j.cardinality() != 0) {
			Algebra alg = new Algebra();
			double i_dot_j = c_i.zDotProduct(c_j);
			double norm_i = alg.norm1(c_i);
			double norm_j = alg.norm1(c_j);
			System.out.println("i dot j: " + i_dot_j + ", norm_i: " + norm_i
					+ ", norm_j: " + norm_j);
			return i_dot_j / norm_i * norm_j;
		} else {
			return 0.0;
		}
	}
	

	/**
	 * @param args
	 * 
	 */
	@SuppressWarnings(value = "unchecked")
	public static void main(String[] args) {
		

		DBCollection venue_coll, user_coll;
		String affinityFilename = "affinity.ser";
		String A_eigenFilename = "a_eigen.ser";
		File f = null;
		File g = null;
		SparseDoubleMatrix2D A = null;
		DoubleMatrix2D D = null;
		EigenvalueDecomposition A_eigen = null;
		DoubleMatrix2D L = null;
		DoubleMatrix2D L_norm = null;
		FileInputStream fis = null;
		ObjectInputStream in = null;
		f = new File(affinityFilename);
		g = new File(A_eigenFilename);
		if (g.exists()) {
			try {
				fis = new FileInputStream(g);
				in = new ObjectInputStream(fis);
				A_eigen = (EigenvalueDecomposition) in.readObject();
				in.close();
				
				System.out.println(A_eigen);
				
//				L = D - A;

			} catch (IOException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
		}
		else if (f.exists()) {
			try {
				fis = new FileInputStream(f);
				in = new ObjectInputStream(fis);
				A = (SparseDoubleMatrix2D) in.readObject();
				in.close();
				
				A_eigen = new EigenvalueDecomposition(A);
				D = A_eigen.getD();
				
				FileOutputStream fos = null;
				ObjectOutputStream out = null;
				fos = new FileOutputStream(g);
				out = new ObjectOutputStream(fos);
				out.writeObject(A_eigen);
				out.close();
				
				System.out.println(D);
			} catch (IOException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
		} else {
			try {
				// Load the collected and pre-processed venue and user data from
				// MongoDB (see
				// https://github.com/brendte/livehoods-twitter/tree/develop for
				// this ruby code)
				Mongo mongo = new Mongo("ds033157.mongolab.com", 33157);
				DB db = mongo.getDB("heroku_app4504006");
				char[] password = "a9cotq6m6dg4llvm3iut5hglag".toCharArray();
				boolean auth = db.authenticate("heroku_app4504006", password);
				System.out.println("auth: " + auth);
				venue_coll = db.getCollection("philadelphia_grid");
				user_coll = db.getCollection("philadelphia_users");
				DBCursor venue_cur = venue_coll.find();
				DBCursor user_cur = user_coll.find();
				ArrayList<DBObject> venues = new ArrayList<DBObject>();
				ArrayList<DBObject> users = new ArrayList<DBObject>();
				for (DBObject venue_rec : venue_cur) {
					venues.add(venue_rec);
				}
				venue_cur.close();
				for (DBObject user_rec : user_cur) {
					users.add(user_rec);
				}
				user_cur.close();
				mongo.close();

				// Build the final venue checkin vectors from MongoDB venue and
				// user collections
				int venueArraySize = venues.size();
				int c_vSize = users.size();
				System.out.println("venues: " + venueArraySize);
				System.out.println("users: " + c_vSize);
				ArrayList<SparseDoubleMatrix1D> venueCheckinVectors = new ArrayList<SparseDoubleMatrix1D>();
				venueCheckinVectors.ensureCapacity(venueArraySize);
				for (int i = 0; i <= venueArraySize; i++) {
					venueCheckinVectors.add(null);
				}
				System.out.println(venueCheckinVectors.size());
				for (DBObject venue : venues) {
					int venueID = ((Integer) venue.get("box_id")).intValue();
					SparseDoubleMatrix1D c_v = new SparseDoubleMatrix1D(c_vSize);
					Map venueMap = venue.toMap();
					Set<Map.Entry<String, Integer>> checkinBagMap = ((BasicDBObject) venueMap.get("check_in_bag")).toMap().entrySet();
					for (Map.Entry<String, Integer> userCheckin : checkinBagMap) {
						c_v.setQuick(
								new Integer(userCheckin.getKey()).intValue(),
								1.0 * userCheckin.getValue().intValue());
					}
					venueCheckinVectors.add(venueID, c_v);
				}

				// build affinity matrix A
				int m = 10;
				double alpha = 0.01;
				int k_min = 30;
				int k_max = 45;
				double tau = 0.4;
				A = new SparseDoubleMatrix2D(venueArraySize, venueArraySize);
				for (int i = 1; i <= venueArraySize; i++) {
					SparseDoubleMatrix1D c_i = venueCheckinVectors.get(i);
					for (int j = 1; j <= venueArraySize; j++) {
						SparseDoubleMatrix1D c_j = venueCheckinVectors.get(j);
						// if necessary, add distance(i, j) calculation here
						double affinityScore = Cluster.cosineSimularity(c_i,
								c_j);
						if (affinityScore != 0.0) {
							affinityScore += alpha;
							A.setQuick(i, j, affinityScore);
						}
						System.out.println("done: (" + i + "," + j
								+ "). affinity score: " + affinityScore);
					}
				}
				System.out.println("Cardinality of A: " + A.cardinality());
				FileOutputStream fos = null;
				ObjectOutputStream out = null;

				fos = new FileOutputStream(f);
				out = new ObjectOutputStream(fos);
				out.writeObject(A);
				out.close();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (MongoException e) {
				e.printStackTrace();
			}
		}
	}
}
