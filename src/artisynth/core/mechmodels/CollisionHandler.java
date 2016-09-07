/**
 * Copyright (c) 2014, by the Authors: John E Lloyd (UBC)
 *
 * This software is freely available under a 2-clause BSD license. Please see
 * the LICENSE file in the ArtiSynth distribution directory for details.
 */
package artisynth.core.mechmodels;

import java.util.*;

import maspack.collision.*;
import maspack.geometry.*;
import maspack.matrix.*;
import maspack.properties.*;
import maspack.render.*;
import maspack.render.color.*;
import maspack.render.Renderer.Shading;
import maspack.util.*;
import artisynth.core.mechmodels.MechSystem.ConstraintInfo;
import artisynth.core.mechmodels.MechSystem.FrictionInfo;
import artisynth.core.mechmodels.CollisionBehavior.Method;
import artisynth.core.modelbase.ComponentUtils;
import artisynth.core.util.TimeBase;

public class CollisionHandler extends ConstrainerBase 
   implements HasRenderProps, Renderable {

   SignedDistanceCollider mySDCollider;
   LinkedHashMap<ContactPoint,ContactConstraint> myBilaterals0;
   LinkedHashMap<ContactPoint,ContactConstraint> myBilaterals1;
   ArrayList<ContactConstraint> myUnilaterals;
   int myMaxUnilaterals = 100;

   CollisionManager myManager;
   CollidableBody myCollidable0;
   CollidableBody myCollidable1;
   PolygonalMesh myMesh0;
   PolygonalMesh myMesh1;
   AbstractCollider myCollider;
   CollisionRenderer myRenderer;

   double myFriction;
   double myPenetrationTol = 0.001;
   double myCompliance = 0;
   double myDamping = 0;

   public static enum Ranging {
      FIXED,
      AUTO_EXPAND,
      AUTO_FIT,
   };

   boolean myDrawIntersectionContours = false;
   boolean myDrawIntersectionFaces = false;
   boolean myDrawIntersectionPoints = false;
   boolean myDrawConstraints = false;
   // attributes related to rendering penetration depth
   static ColorMapBase defaultColorMap = new HueColorMap(2.0/3, 0);
   static Ranging defaultDrawPenetrationRanging = Ranging.AUTO_FIT;

   ColorMapBase myColorMap = null;
   Ranging myDrawPenetrationRanging = null;
   DoubleInterval myDrawPenetrationRange = null;
   int myDrawMeshPenetration = -1;

   ContactInfo myLastContactInfo; // last contact info produced by this handler
   ContactInfo myRenderContactInfo; // contact info to be used for rendering

   HashSet<Vertex3d> myAttachedVertices0 = null;
   HashSet<Vertex3d> myAttachedVertices1 = null;
   boolean myAttachedVerticesValid = false;

   CollisionBehavior.Method myMethod;
   public static boolean useSignedDistanceCollider = false;
   
   public static boolean doBodyFaceContact = false;
   public static boolean computeTimings = false;
   
   public boolean reduceConstraints = false;
   private ContactForceBehavior myForceBehavior = null;

   public static PropertyList myProps =
      new PropertyList (CollisionHandler.class, ConstrainerBase.class);

   static private RenderProps defaultRenderProps = new RenderProps();

   static {
      myProps.add (
         "renderProps * *", "render properties for this collision handler",
         defaultRenderProps);
   }

   public PropertyList getAllPropertyInfo() {
      return myProps;
   }

   double getContactNormalLen() {
      if (myManager != null) {
         return myManager.getContactNormalLen();
      }
      else {
         return 0;
      }
      // ModelComponent ancestor = getGrandParent();
      // if (ancestor instanceof CollisionManager) {
      //    return ((CollisionManager)ancestor).getContactNormalLen();
      // }
      // else {
      //    return 0;
      // }
   }

   void setLastContactInfo(ContactInfo info) {
      myLastContactInfo = info;
   }

   public ContactInfo getLastContactInfo() {
      return myLastContactInfo;
   }

   /**
    * Returns the coefficient of friction for this collision pair.
    */
   public double getFriction() {
      return myFriction;
   }

   /**
    * Sets the coeffcient of friction for this collision pair.
    */
   public void setFriction (double mu) {
      myFriction = mu;
   }

   public double getPenetrationTol () {
      return myPenetrationTol;
   }

   public void setPenetrationTol (double tol) {
      myPenetrationTol = tol;
   }

   public void setCompliance (double c) {
      myCompliance = c;
   }

   public double getCompliance() {
      return myCompliance;
   }

   public void setDamping (double d) {
      myDamping = d;
   }

   public double getDamping() {
      return myDamping;
   }

   public boolean isCompliant() {
      return myCompliance != 0 || myForceBehavior != null;
   }

   public void setRigidPointTolerance (double tol) {
      // XXX should we always set this?
      if (myMethod == CollisionBehavior.Method.CONTOUR_REGION) {
         myCollider.setPointTolerance (tol);
      }
   }

   public double getRigidPointTolerance() {
      // XXX should we always set this?
      if (myMethod == CollisionBehavior.Method.CONTOUR_REGION) {
         return myCollider.getPointTolerance();
      }
      else {
         return -1;
      }
   }

   public void setRigidRegionTolerance (double tol) {
      // XXX should we always set this?
      if (myMethod == CollisionBehavior.Method.CONTOUR_REGION) {
         myCollider.setRegionTolerance (tol);
      }
   }

   public double getRigidRegionTolerance() {
      // XXX should we always set this?
      if (myMethod == CollisionBehavior.Method.CONTOUR_REGION) {
         return myCollider.getRegionTolerance();
      }
      else {
         return -1;
      }
   }

   protected boolean isRigid (CollidableBody col) {
      return (col instanceof RigidBody || col instanceof RigidMeshComp);
   }

   public void setDrawIntersectionContours (boolean set) {
      myDrawIntersectionContours = set;
   }

   public boolean isDrawIntersectionContours() {
      return myDrawIntersectionContours;
   }
   
   public void setDrawIntersectionFaces (boolean set) {
      myDrawIntersectionFaces = set;
   }

   public boolean isDrawIntersectionFaces() {
      return myDrawIntersectionFaces;
   }

   public void setDrawIntersectionPoints (boolean set) {
      myDrawIntersectionPoints = set;
   }

   public boolean isDrawIntersectionPoints() {
      return myDrawIntersectionPoints;
   }

   public void setDrawMeshPenetration (int meshNum) {
      if (meshNum < -1 || meshNum > 1) {
         throw new IllegalArgumentException (
            "meshNum must be -1, 0, or 1");
      }
      if (meshNum != -1) {
         if (myColorMap == null) {
            try {
               myColorMap = defaultColorMap.clone();
            }
            catch (Exception e) {
               e.printStackTrace();
            }
         }
         if (myDrawPenetrationRanging == null) {
            myDrawPenetrationRanging = defaultDrawPenetrationRanging;
         }
         if (myDrawPenetrationRange == null) {
            myDrawPenetrationRange = new DoubleInterval(0,0);
         }
      }
      myDrawMeshPenetration = meshNum;      
   }

   public int getDrawMeshPenetration() {
      return myDrawMeshPenetration;
   }

   public DoubleInterval getDrawPenetrationRange() {
      return myDrawPenetrationRange;
   }

   public void setDrawPenetrationRange (DoubleInterval range) {
      myDrawPenetrationRange = new DoubleInterval(range);
   }

   public Ranging getDrawPenetrationRanging() {
      return myDrawPenetrationRanging;
   }

   public void setDrawPenetrationRange (Ranging ranging) {
      myDrawPenetrationRanging = ranging;
   }

   public boolean isReduceConstraints() {
      return reduceConstraints;
   }
   
   public void setReduceConstraints(boolean set) {
      reduceConstraints = set;
   }

   public void setForceBehavior (ContactForceBehavior behavior) {
      myForceBehavior = behavior;
   }
   
   public ContactForceBehavior getForceBehavior() {
      return myForceBehavior;
   }

   public ColorMapBase getColorMap() {
      return myColorMap;
   }

   public void setColorMap (ColorMapBase map) {
      myColorMap = map.copy();
   }
   
   public CollisionHandler (CollisionManager manager) {
      myBilaterals0 = new LinkedHashMap<ContactPoint,ContactConstraint>();
      myBilaterals1 = new LinkedHashMap<ContactPoint,ContactConstraint>();
      myUnilaterals = new ArrayList<ContactConstraint>();
      myCollider = SurfaceMeshCollider.newCollider();
      myManager = manager;
   }

   public CollisionHandler (
      CollisionManager manager,
      CollidableBody col0, CollidableBody col1, CollisionBehavior behav) {

      this (manager);
      set (col0, col1, behav);
   }
   
   void set (
      CollidableBody col0, CollidableBody col1, CollisionBehavior behav) {
      myCollidable0 = col0;
      myCollidable1 = col1;
      Method method = behav.getMethod();
      if (method == Method.DEFAULT) {
         if (isRigid(col0) && isRigid(col1)) {
            method = CollisionBehavior.Method.CONTOUR_REGION;
         }
         else {
            method = CollisionBehavior.Method.VERTEX_PENETRATION;
         }
      }
      if (method != CollisionBehavior.Method.CONTOUR_REGION &&
          isRigid(col0) && !isRigid(col1)) {
         myCollidable0 = col1;
         myCollidable1 = col0;         
      }
      myMethod = method;
      setFriction (behav.getFriction());
   }

   public static boolean attachedNearContact (
      ContactPoint cpnt, Collidable collidable, 
      Set<Vertex3d> attachedVertices) {
   
      // Basic idea:
      // for (each vertex v associated with cpnt) {
      //    if (all contact masters of v are attached to collidable1) 
      //       return true;
      //    }
      // }
      if (attachedVertices == null || attachedVertices.size() == 0) {
         return false;
      }
      Vertex3d[] vtxs = cpnt.myVtxs;
      for (int i=0; i<vtxs.length; i++) {
         if (attachedVertices.contains (vtxs[i])) {
            return true;
         }
      }
      if (vtxs.length == 1) {
         // vertices associated with cpnt expanded to include surrounding
         // vertices as well.
         Iterator<HalfEdge> it = vtxs[0].getIncidentHalfEdges();
         while (it.hasNext()) {
            HalfEdge he = it.next();
            if (attachedVertices.contains (he.getHead())) {
               return true;
            }
         }
      }
      return false;
   }

   protected void putContact (
      HashMap<ContactPoint,ContactConstraint> contacts, 
      ContactConstraint cons) {
      if (cons.myIdentifyByPoint1) {
         contacts.put (cons.myCpnt1, cons);
      }
      else {
         contacts.put (cons.myCpnt0, cons);
      }
   }

   public ContactConstraint getContact (
      HashMap<ContactPoint,ContactConstraint> contacts,
      ContactPoint cpnt0, ContactPoint cpnt1, 
      boolean hashUsingFace, double distance) {

      ContactConstraint cons = null;
      cons = contacts.get (hashUsingFace ? cpnt1 : cpnt0);

      if (cons == null) {
         cons = new ContactConstraint (this, cpnt0, cpnt1);
         cons.myIdentifyByPoint1 = hashUsingFace;
         putContact (contacts, cons);
         return cons;
      }
      else { // contact already exists
         double lam = cons.getImpulse();
         //do not activate constraint if contact is trying to separate
         if (lam < 0) {
            return null;
         }
         else if (cons.isActive() && -cons.getDistance() >= distance) {
            // if constraint exists and it has already been set with a distance
            // greater than the current one, don't return anything; leave the
            // current one alone. This is for cases where the same feature maps
            // onto more than one contact.
            return null;
         }
         else {
            // update contact points
            cons.setContactPoints (cpnt0, cpnt1);
            return cons;
         }
      }
   }

   public double computeCollisionConstraints (double t) {

      clearRenderData();

      myMesh0 = myCollidable0.getCollisionMesh();
      myMesh1 = myCollidable1.getCollisionMesh();

      ContactInfo info = myCollider.getContacts (myMesh0, myMesh1);
      double maxpen;
      switch (myMethod) {
         case VERTEX_PENETRATION: 
         case VERTEX_PENETRATION_BILATERAL:
         case VERTEX_EDGE_PENETRATION: {
            maxpen = computeVertexPenetrationConstraints (
               info, myCollidable0, myCollidable1);
            break;
         }
         case CONTOUR_REGION: {
            maxpen = computeContourRegionConstraints (
               info, myCollidable0, myCollidable1);
            break;
         }
         case INACTIVE: {
            // do nothing
            maxpen = 0;
            break;
         }
         default: {
            throw new InternalErrorException (
               "Unimplemented collision method: "+myMethod);
         }
      }
      myLastContactInfo = info;
      return maxpen;
   }

   Collidable getCollidable (PenetratingPoint cpp) {
      if (cpp.vertex.getMesh() == myMesh0) {
         return myCollidable0;
      }
      else {
         return myCollidable1;
      }
   }

   public Collidable getCollidable (int idx) {
      switch (idx) {
         case 0: {
            return myCollidable0;
         }
         case 1: {
            return myCollidable1;
         }
         default: {
            throw new ArrayIndexOutOfBoundsException ("Index must be 0 or 1");
         }
      }
   }      

   double setVertexFace (
      ContactConstraint cons, PenetratingPoint cpp,
      CollidableBody collidable0, CollidableBody collidable1) {

      //cons.setContactPoint2 (cpp.position, cpp.face, cpp.coords);
      cpp.face.computeNormal (cons.myNormal);
      PolygonalMesh mesh = collidable1.getCollisionMesh();
      // convert to world coordinates if necessary
      if (!mesh.meshToWorldIsIdentity()) {
         cons.myNormal.transform (mesh.getMeshToWorld());
      }
      cons.assignMasters (collidable0, collidable1);

      // This should be -cpp.distance - do we need to compute this?
      Vector3d disp = new Vector3d();
      disp.sub(cons.myCpnt0.myPoint, cons.myCpnt1.myPoint);
      double dist = disp.dot(cons.myNormal);

      return dist;
   }

   double setEdgeEdge (
      ContactConstraint cons, EdgeEdgeContact eecs,
      CollidableBody collidable0, CollidableBody collidable1) {

      cons.myNormal.negate (eecs.point1ToPoint0Normal);
      cons.assignMasters (collidable0, collidable1);
      return -eecs.displacement;
   }

   boolean hashContactUsingFace (
      CollidableBody collidable0, CollidableBody collidable1) {
      // for vertex penetrating constraints, if collidable0 has low DOF and its
      // mesh has more vertices than that of collidable1, then we hash the
      // contact using the face on collidable1 instead of the vertex on
      // collidable since that is more likely to persist.
      PolygonalMesh mesh0 = collidable0.getCollisionMesh();
      PolygonalMesh mesh1 = collidable1.getCollisionMesh();
      return (!isCompliant() && hasLowDOF (collidable0) &&
              mesh0.numVertices() > mesh1.numVertices());
   }

   protected boolean isCompletelyAttached (
      DynamicComponent comp,
      CollidableBody collidable0, CollidableBody collidable1) {
      DynamicAttachment attachment = comp.getAttachment();
      if (attachment == null) {
         return false;
      }
      else {
         DynamicComponent[] attachMasters = attachment.getMasters();
         for (int k=0; k<attachMasters.length; k++) {
            CollidableDynamicComponent mcomp = null;
            if (attachMasters[k] instanceof CollidableDynamicComponent) {
               mcomp = (CollidableDynamicComponent)attachMasters[k];
            }
            if (collidable0.containsContactMaster (mcomp)) {
               // ignore
               continue;
            }
            if (mcomp == null || !collidable1.containsContactMaster (mcomp)) {
               return false;
            }
         }
      }
      return true;
   }
   
   protected boolean isContainedIn (
      DynamicComponent comp, CollidableBody collidable) {
      if (comp instanceof CollidableDynamicComponent) {
         return collidable.containsContactMaster (
            (CollidableDynamicComponent)comp);
      }
      else {
         return false;
      }
   }
   
   protected boolean vertexAttachedToCollidable (
      Vertex3d vtx, CollidableBody collidable0, CollidableBody collidable1) {
      ArrayList<ContactMaster> masters = new ArrayList<ContactMaster>();
      collidable0.getVertexMasters (masters, vtx);
      // vertex is considered attached if 
      // (a) all masters are completely attached to collidable1, or
      // (b) all masters are actually contained in collidable1
      for (int i=0; i<masters.size(); i++) {
         DynamicComponent comp = masters.get(i).myComp;
         if (!isCompletelyAttached (comp, collidable0, collidable1) &&
             !isContainedIn (comp, collidable1)) {
            return false;
         }
      }
      return true;
   }


   protected HashSet<Vertex3d> computeAttachedVertices (
      CollidableBody collidable0, CollidableBody collidable1) {

      if (isRigid (collidable0)) {
         return null;
      }
      PolygonalMesh mesh = collidable0.getCollisionMesh();
      HashSet<Vertex3d> attached = new HashSet<Vertex3d>();
      for (Vertex3d vtx : mesh.getVertices()) {
         if (vertexAttachedToCollidable (vtx, collidable0, collidable1)) {
            attached.add (vtx);
         }
      }
      return attached.size() > 0 ? attached : null;
   }

   protected void updateAttachedVertices() {
      if (!myAttachedVerticesValid) {
         myAttachedVertices0 =
            computeAttachedVertices (myCollidable0, myCollidable1);
         myAttachedVertices1 =
            computeAttachedVertices (myCollidable1, myCollidable0);
         myAttachedVerticesValid = true;
      }
   }

   double computeEdgePenetrationConstraints (
      ArrayList<EdgeEdgeContact> eecs,
      CollidableBody collidable0, CollidableBody collidable1) {

      double maxpen = 0;

      for (EdgeEdgeContact eec : eecs) {
         // Check if the contact has already been corrected by other contact
         // corrections.
         //if (eec.calculate()) {

         ContactPoint pnt0, pnt1;
         pnt0 = new ContactPoint (eec.point0, eec.edge0, eec.s0);
         pnt1 = new ContactPoint (eec.point1, eec.edge1, eec.s1);

         ContactConstraint cons = getContact (
            myBilaterals0, pnt0, pnt1, false, eec.displacement);
         // As long as the constraint exists and is not already marked 
         // as active, then we add it
         if (cons != null) {
            cons.setActive (true);

            double dist = setEdgeEdge (cons, eec, collidable0, collidable1);
            if (!cons.isControllable()) {
               cons.setActive (false);
               continue;
            } 
            cons.setDistance (dist);
            if (-dist > maxpen) {
               maxpen = -dist;
            }               
         }
         //}
      }
      return maxpen;
   }

   double computeVertexPenetrationConstraints (
      ArrayList<PenetratingPoint> points,
      CollidableBody collidable0, CollidableBody collidable1) {

      double nrmlLen = getContactNormalLen();
      double maxpen = 0;
      Vector3d normal = new Vector3d();
      boolean hashUsingFace = hashContactUsingFace (collidable0, collidable1);

      updateAttachedVertices();

      for (PenetratingPoint cpp : points) {
         ContactPoint pnt0, pnt1;
         pnt0 = new ContactPoint (cpp.vertex);
         pnt1 = new ContactPoint (cpp.position, cpp.face, cpp.coords);
         
         HashSet<Vertex3d> attachedVtxs0 = myAttachedVertices0;
         HashSet<Vertex3d> attachedVtxs1 = myAttachedVertices1;
         if (collidable0 == myCollidable1) {
            // flip attached vertex lists
            attachedVtxs0 = myAttachedVertices1;
            attachedVtxs1 = myAttachedVertices0;
         }

         if (!collidable0.allowCollision (
                pnt0, collidable1, attachedVtxs0) ||
             !collidable1.allowCollision (
                pnt1, collidable0, attachedVtxs1)) {
            continue;
         }

         ContactConstraint cons;
         if (collidable0 == myCollidable0) {
            cons = getContact (
               myBilaterals0, pnt0, pnt1, hashUsingFace, cpp.distance);
         }
         else {
            cons = getContact (
               myBilaterals1, pnt0, pnt1, hashUsingFace, cpp.distance);
         }
         
         // As long as the constraint exists and is not already marked 
         // as active, then we add it
         if (cons != null) {
            cons.setActive (true);

            double dist = setVertexFace (cons, cpp, collidable0, collidable1);
            if (!cons.isControllable()) {
               cons.setActive (false);
               continue;
            }
            cons.setDistance (dist);
            if (nrmlLen != 0) {
               // compute normal from scratch because previous contacts
               // may have caused it to change
               cpp.face.computeNormal (normal);
               PolygonalMesh mesh1 = collidable1.getCollisionMesh();
               if (!mesh1.meshToWorldIsIdentity()) {
                  normal.transform (mesh1.getMeshToWorld());
               }
               addLineSegment (cpp.position, normal, nrmlLen);
            }
            // activateContact (cons, dist, data);
            if (-dist > maxpen) {
               maxpen = -dist;
            }
         }
      }
      return maxpen;
   }

   /**
      multiple constraint handling
      mark all existing constraints inactive
      for (each contact c) {
         if (c matches an existing constraint con) {
            if (con is not trying to separate) {
               if (con is active) {
                  if (penetration is greater) {
                     return con;
                  }
                  else {
                     return null;
                  }
               }
               else {
                  return con;
               }
            }
         }
   */

   boolean hasLowDOF (CollidableBody collidable) {
      // XXX should formalize this better
      return isRigid (collidable);
   }

   double computeVertexPenetrationConstraints (
      ContactInfo info, CollidableBody collidable0, CollidableBody collidable1) {
      double maxpen = 0;
      clearContactActivity();
      if (info != null) {
         maxpen = computeVertexPenetrationConstraints (
            info.getPenetratingPoints0(), collidable0, collidable1);
         if (!hasLowDOF (collidable1) || doBodyFaceContact) {
            double pen = computeVertexPenetrationConstraints (
               info.getPenetratingPoints1(), collidable1, collidable0);
            if (pen > maxpen) {
               maxpen = pen;
            }
         }
         if (myMethod == CollisionBehavior.Method.VERTEX_EDGE_PENETRATION) {
            double pen = computeEdgePenetrationConstraints (
               info.getEdgeEdgeContacts(), collidable0, collidable1);
            if (pen > maxpen) {
               maxpen = pen;
            }
         }
      }
      removeInactiveContacts();
      //printContacts ("%g");
      return maxpen;
   }

   double computeContourRegionConstraints (
      ContactInfo info, CollidableBody collidable0, CollidableBody collidable1) {

      myUnilaterals.clear();
      double maxpen = 0;

      double nrmlLen = getContactNormalLen();
      clearRenderData();

      // Currently, no correspondence is established between new contacts and
      // previous contacts. If there was, then we could set the multipliers for
      // the new contacts to something based on the old contacts, thereby
      // providing initial "warm start" values for the solver.
      int numc = 0;
      if (info != null) {

         for (ContactPlane region : info.getContactPlanes()) {
            for (Point3d p : region.points) {
               if (numc >= myMaxUnilaterals)
                  break;

               ContactConstraint c = new ContactConstraint(this);

               c.setContactPoint0 (p);
               c.equateContactPoints();
               c.setNormal (region.normal);
               c.assignMasters (collidable0, collidable1);

               if (nrmlLen != 0) {
                  addLineSegment (p, region.normal, nrmlLen);
               }

               maxpen = region.depth;
               c.setDistance (-region.depth);
               myUnilaterals.add (c);
               numc++;
            }
         }
      }
      setLastContactInfo(info);
      return maxpen;
   }

   void clearContactData() {
      myBilaterals0.clear();
      myBilaterals1.clear();
      myUnilaterals.clear();
   }

   public void clearContactActivity() {
      for (ContactConstraint c : myBilaterals0.values()) {
         c.setActive (false);
         c.setDistance (0);
      }
      for (ContactConstraint c : myBilaterals1.values()) {
         c.setActive (false);
         c.setDistance (0);
      }
   }

   public void removeInactiveContacts() {
      Iterator<ContactConstraint> it;
      it = myBilaterals0.values().iterator();
      while (it.hasNext()) {
         ContactConstraint c = it.next();
         if (!c.isActive()) {
            it.remove();
            //mycontactschanged = true;
         }
      }
      it = myBilaterals1.values().iterator();
      while (it.hasNext()) {
         ContactConstraint c = it.next();
         if (!c.isActive()) {
            it.remove();
            //mycontactschanged = true;
         }
      }
   }

   private void printContacts(String fmtStr) {
      Iterator<ContactConstraint> it;
      it = myBilaterals0.values().iterator();
      System.out.println ("mesh0");
      while (it.hasNext()) {
         ContactConstraint c = it.next();
         System.out.println (" " + c.toString(fmtStr));
      }
      it = myBilaterals1.values().iterator();
      System.out.println ("mesh1");
      while (it.hasNext()) {
         ContactConstraint c = it.next();
         System.out.println (" " + c.toString(fmtStr));
      }
   }

   /** 
    * automatically compute compliance and damping for a given
    * penetration tolerance and acceleration.
    *
    * @param acc acceleration
    * @param tol desired penetration tolerance 
    */
   public void autoComputeCompliance (double acc, double tol) {
      // todo: does getmass() do what we want here?
      double mass = myCollidable0.getMass() + myCollidable1.getMass();
      double dampingRatio = 1;
      myCompliance = tol/(acc*mass);
      myDamping = dampingRatio*2*Math.sqrt(mass/myCompliance);
   }   

   void addCollisionComponent (
      ContactConstraint con, Point3d pnt, Feature feat) {
   }

   // begin constrainer implementation

   public double updateConstraints (double t, int flags) {
      if ((flags & MechSystem.COMPUTE_CONTACTS) != 0) {
         return computeCollisionConstraints (t);
      }
      else if ((flags & MechSystem.UPDATE_CONTACTS) != 0) {
         // right now just leave the same contacts in place ...
         return 0;
      }
      else {
         return 0;
      }
   }

   private void getConstraintComponents (
      HashSet<DynamicComponent> set, Collection<ContactConstraint> contacts) {
      for (ContactConstraint cc : contacts) {
         for (ContactMaster cm : cc.getMasters()) {
            set.add (cm.myComp);
         }
      }
   }
   
   public void getConstrainedComponents (List<DynamicComponent> list) {
      HashSet<DynamicComponent> set = new HashSet<DynamicComponent>();
      getConstrainedComponents (set);
      list.addAll (set);
   }
   
   public void getConstrainedComponents (HashSet<DynamicComponent> set) {
      getConstraintComponents (set, myBilaterals0.values());
      getConstraintComponents (set, myBilaterals1.values());
      getConstraintComponents (set, myUnilaterals);      
   }
   
   @Override
   public void getBilateralSizes (VectorNi sizes) {
      for (int i=0; i<myBilaterals0.size(); i++) {
         sizes.append (1);
      }
      for (int i=0; i<myBilaterals1.size(); i++) {
         sizes.append (1);
      }
   }

   @Override
   public void getUnilateralSizes (VectorNi sizes) {
      for (int i=0; i<myUnilaterals.size(); i++) {
         sizes.append (1);
      }
   }

   public void getBilateralConstraints (List<ContactConstraint> list) {
      list.addAll (myBilaterals0.values());
      list.addAll (myBilaterals1.values());
   }

   @Override
   public int addBilateralConstraints (
      SparseBlockMatrix GT, VectorNd dg, int numb) {

      double[] dbuf = (dg != null ? dg.getBuffer() : null);

      for (ContactConstraint c : myBilaterals0.values()) {
         c.addConstraintBlocks (GT, GT.numBlockCols());
         if (dbuf != null) {
            dbuf[numb] = c.getDerivative();
         }
         numb++;
      }
      for (ContactConstraint c : myBilaterals1.values()) {
         c.addConstraintBlocks (GT, GT.numBlockCols());
         if (dbuf != null) {
            dbuf[numb] = c.getDerivative();
         }
         numb++;
      }
      return numb;
   }

   @Override
   public int getBilateralInfo(ConstraintInfo[] ginfo, int idx) {
      
      double[] fres = new double[] { 0, getCompliance(), getDamping() };
      
      for (ContactConstraint c : myBilaterals0.values()) {
         c.setSolveIndex (idx);
         ConstraintInfo gi = ginfo[idx++];
         if (c.getDistance() < -myPenetrationTol) {
            gi.dist = (c.getDistance() + myPenetrationTol);
         }
         else {
            gi.dist = 0;
         }
         if (myForceBehavior != null) {
            myForceBehavior.computeResponse (
               fres, c.myDistance, c.myCpnt0, c.myCpnt1, c.myNormal);
         }
         gi.force =      fres[0];
         gi.compliance = fres[1];
         gi.damping =    fres[2];
      }
      for (ContactConstraint c : myBilaterals1.values()) {
         c.setSolveIndex (idx);
         ConstraintInfo gi = ginfo[idx++];
         if (c.getDistance() < -myPenetrationTol) {
            gi.dist = (c.getDistance() + myPenetrationTol);
         }
         else {
            gi.dist = 0;
         }
         if (myForceBehavior != null) {
            myForceBehavior.computeResponse (
               fres, c.myDistance, c.myCpnt0, c.myCpnt1, c.myNormal);
         }
         gi.force =      fres[0];
         gi.compliance = fres[1];
         gi.damping =    fres[2];
      }
      return idx;
   }

   @Override
   public int setBilateralImpulses (VectorNd lam, double h, int idx) {
      for (ContactConstraint c : myBilaterals0.values()) {
         c.setImpulse (lam.get (idx++));
      }
      for (ContactConstraint c : myBilaterals1.values()) {
         c.setImpulse (lam.get (idx++));
      }
      return idx;
   }

   @Override
   public int getBilateralImpulses (VectorNd lam, int idx) {
      for (ContactConstraint c : myBilaterals0.values()) {
         lam.set (idx++, c.getImpulse());
      }
      for (ContactConstraint c : myBilaterals1.values()) {
         lam.set (idx++, c.getImpulse());
      }
      return idx;
   }

   @Override
   public void zeroImpulses() {
      for (ContactConstraint c : myBilaterals0.values()) {
         c.setImpulse (0);
      }   
      for (ContactConstraint c : myBilaterals1.values()) {
         c.setImpulse (0);
      }   
      for (int i=0; i<myUnilaterals.size(); i++) {
         myUnilaterals.get(i).setImpulse (0);
      }
   }

   @Override
   public int addUnilateralConstraints (
      SparseBlockMatrix NT, VectorNd dn, int numu) {

      double[] dbuf = (dn != null ? dn.getBuffer() : null);
      int bj = NT.numBlockCols();
      for (int i=0; i<myUnilaterals.size(); i++) {
         ContactConstraint c = myUnilaterals.get(i);
         c.addConstraintBlocks (NT, bj++);
         if (dbuf != null) {
            dbuf[numu] = c.getDerivative();
         }
         numu++;
      }
      return numu;
   }

   @Override
   public int getUnilateralInfo (ConstraintInfo[] ninfo, int idx) {

      double[] fres = new double[] { 0, getCompliance(), getDamping() };
      
      for (int i=0; i<myUnilaterals.size(); i++) {
         ContactConstraint c = myUnilaterals.get(i);
         c.setSolveIndex (idx);
         ConstraintInfo ni = ninfo[idx++];
         if (c.getDistance() < -myPenetrationTol) {
            ni.dist = (c.getDistance() + myPenetrationTol);
         }
         else {
            ni.dist = 0;
         }
         if (myForceBehavior != null) {
            myForceBehavior.computeResponse (
               fres, c.myDistance, c.myCpnt0, c.myCpnt1, c.myNormal);
         }
         ni.force =      fres[0];
         ni.compliance = fres[1];
         ni.damping =    fres[2];
      }
      return idx;
   }

   @Override
   public int setUnilateralImpulses (VectorNd the, double h, int idx) {
      double[] buf = the.getBuffer();
      for (int i=0; i<myUnilaterals.size(); i++) {
         myUnilaterals.get(i).setImpulse (buf[idx++]);
      }
      return idx;
   }

   @Override
   public int getUnilateralImpulses (VectorNd the, int idx) {
      double[] buf = the.getBuffer();
      for (int i=0; i<myUnilaterals.size(); i++) {
         buf[idx++] = myUnilaterals.get(i).getImpulse();
      }
      return idx;
   }

   public int maxFrictionConstraintSets() {
      return myBilaterals0.size() + myBilaterals1.size() + myUnilaterals.size();
   }

   @Override
   public int addFrictionConstraints (
      SparseBlockMatrix DT, FrictionInfo[] finfo, int numf) {

      for (ContactConstraint c : myBilaterals0.values()) {
         numf = c.add1DFrictionConstraints (DT, finfo, myFriction, numf);
      }
      for (ContactConstraint c : myBilaterals1.values()) {
         numf = c.add1DFrictionConstraints (DT, finfo, myFriction, numf);
      }
      for (int i=0; i<myUnilaterals.size(); i++) {
         ContactConstraint c = myUnilaterals.get(i);
         if (Math.abs(c.getImpulse())*myFriction < 1e-4) {
            continue;
         }
         numf = c.add2DFrictionConstraints (DT, finfo, myFriction, numf);
      }
      return numf;
   }

   public int numBilateralConstraints() {
      return myBilaterals0.size() + myBilaterals1.size();
   }

   public int numUnilateralConstraints() {
      return myUnilaterals.size();
   }

   /** 
    * {@inheritDoc}
    */
   public void skipAuxState (DataBuffer data) {
      int numb = data.zget();
      int numu = data.zget();
      for (int i=0; i<numu+numb; i++) {
         ContactConstraint.skipState (data);
      }
   }

   /** 
    * {@inheritDoc}
    */
   public void getAuxState (DataBuffer data) {

      data.zput (myBilaterals0.size());
      data.zput (myBilaterals1.size());
      data.zput (myUnilaterals.size());
      for (ContactConstraint c : myBilaterals0.values()) {
         c.getState (data);
      }
      for (ContactConstraint c : myBilaterals1.values()) {
         c.getState (data);
      }
      for (int i=0; i<myUnilaterals.size(); i++) {
         myUnilaterals.get(i).getState (data);
      }
   }

   /** 
    * {@inheritDoc}
    */
   public void setAuxState (DataBuffer data) {

      clearContactData();
      int numb0 = data.zget();
      int numb1 = data.zget();
      int numu = data.zget();
      for (int i=0; i<numb0; i++) {
         ContactConstraint c = new ContactConstraint(this);
         c.setState (data, myCollidable0, myCollidable1);
         putContact (myBilaterals0, c);
      }        
      for (int i=0; i<numb1; i++) {
         ContactConstraint c = new ContactConstraint(this);
         c.setState (data, myCollidable1, myCollidable0);
         putContact (myBilaterals1, c);
      }        
      for (int i=0; i<numu; i++) {
         ContactConstraint c = new ContactConstraint(this);
         c.setState (data, myCollidable0, myCollidable1);
         myUnilaterals.add (c);
      }        
   }

   /** 
    * {@inheritDoc}
    */
   public void getInitialAuxState (DataBuffer newData, DataBuffer oldData) {

      // just create a state in which there are no contacts
      newData.zput (0); // no bilaterals
      newData.zput (0); // no bilaterals
      newData.zput (0); // no unilaterals
   }

   /* ===== Begin Render methods ===== */

   class FaceSeg {
      Face face;
      Point3d p0;
      Point3d p1;
      Point3d p2;
      Vector3d nrm;

      public FaceSeg(Face face) {
         this.face = face;
         nrm = face.getWorldNormal();
         HalfEdge he = face.firstHalfEdge();
         p0 = he.head.getWorldPoint();
         he = he.getNext();
         p1 = he.head.getWorldPoint();
         he = he.getNext();
         p2 = he.head.getWorldPoint();
         he = he.getNext();
      }
   }

   class LineSeg {
      float[] coords0;
      float[] coords1;

      public LineSeg (Point3d pnt0, Vector3d nrm, double len) {
         coords0 = new float[3];
         coords1 = new float[3];

         coords0[0] = (float)pnt0.x;
         coords0[1] = (float)pnt0.y;
         coords0[2] = (float)pnt0.z;

         coords1[0] = (float)(pnt0.x + len*nrm.x);
         coords1[1] = (float)(pnt0.y + len*nrm.y);
         coords1[2] = (float)(pnt0.z + len*nrm.z);
      }
   }

   class ConstraintSeg extends LineSeg {
      float lambda;

      public ConstraintSeg (
         Point3d pnt0, Vector3d nrm, double len, double lam) {
         super (pnt0, nrm, len);
         lambda = (float)lam;
      }
   }

   ArrayList<LineSeg> myLineSegments;
   //private ArrayList<LineSeg> myRenderSegments; // for rendering
   ArrayList<FaceSeg> myFaceSegments; 
   //private ArrayList<FaceSeg> myRenderFaces; // for rendering
   //private ArrayList<ConstraintSeg> myRenderConstraints; // for rendering

   void clearRenderData() {
      myLineSegments = new ArrayList<LineSeg>();
      myFaceSegments = null;
   }

   void addLineSegment (Point3d pnt0, Vector3d nrm, double len) {
      myLineSegments.add (new LineSeg (pnt0, nrm, len));
   }

   void initialize() {
      myLineSegments = null;
      myLastContactInfo = null;
   }

   public void prerender (RenderProps props) {
      if (myRenderer == null) {
         myRenderer = new CollisionRenderer();
      }
      if (myDrawIntersectionFaces &&
          myLastContactInfo != null &&
          myFaceSegments == null) {
         ArrayList<TriTriIntersection> intersections = 
            myLastContactInfo.getIntersections();
         if (intersections != null) {
            myFaceSegments = new ArrayList<FaceSeg>();
            buildFaceSegments (intersections, myFaceSegments);
         }
      }
      myRenderer.prerender (this, props);
   }

   public void prerender (RenderList list) {
      prerender (myRenderProps);
   }

   protected void findInsideFaces (
      Face face, BVFeatureQuery query, PolygonalMesh mesh,
      ArrayList<FaceSeg> faces) {

      face.setVisited();
      Point3d pnt = new Point3d();
      HalfEdge he = face.firstHalfEdge();
      for (int i=0; i<3; i++) {
         if (he.opposite != null) {
            Face oFace = he.opposite.getFace();
            if (!oFace.isVisited()) {
               // check if inside
               oFace.computeWorldCentroid(pnt);

               boolean inside = query.isInsideOrientedMesh(mesh, pnt, -1);
               if (inside) {
                  FaceSeg seg = new FaceSeg(oFace); 
                  faces.add(seg);
                  findInsideFaces(oFace, query, mesh, faces);
               }
            }
         }
         he = he.getNext();
      }

   }

   protected void buildFaceSegments (
      ArrayList<TriTriIntersection> intersections, ArrayList<FaceSeg> faces) {

      BVFeatureQuery query = new BVFeatureQuery();

      PolygonalMesh mesh0 = myCollidable0.getCollisionMesh();
      PolygonalMesh mesh1 = myCollidable1.getCollisionMesh();

      // mark faces as visited and add segments
      for (TriTriIntersection isect : intersections) {
         isect.face0.setVisited();
         isect.face1.setVisited();

         // add partials?
      }

      // mark interior faces and add segments
      for (TriTriIntersection isect : intersections) {
         if (isect.face0.getMesh() != mesh0) {
            findInsideFaces(isect.face0, query, mesh0, faces);
            findInsideFaces(isect.face1, query, mesh1, faces);
         } else {
            findInsideFaces(isect.face0, query, mesh1, faces);
            findInsideFaces(isect.face1, query, mesh0, faces);
         }
      }

      for (TriTriIntersection isect : intersections) {
         isect.face0.clearVisited();
         isect.face1.clearVisited();
      }

      // clear visited flag for use next time
      for (FaceSeg seg : faces) {
         seg.face.clearVisited();
      }

   }

   public RenderProps createRenderProps() {
      return RenderProps.createRenderProps (this);
   }

   public void render (Renderer renderer, int flags) {
      render (renderer, myRenderProps, flags);
   }

   // Twist lastmomentumchange = null;

   public void render (Renderer renderer, RenderProps props, int flags) {

      if (myRenderer == null) {
         myRenderer = new CollisionRenderer();
      }
      myRenderer.render (renderer, this, props, flags);
   }

   public void updateBounds (Vector3d pmin, Vector3d pmax) {
      if (myRenderContactInfo != null) {
         ArrayList<IntersectionContour> contours = 
            myRenderContactInfo.getContours();
         if (contours != null) {
            for (IntersectionContour contour : contours) {
               for (IntersectionPoint p : contour) {
                  p.updateBounds (pmin, pmax);
               }
            }
         }
      }
      if (myLineSegments != null) {
         Point3d pnt = new Point3d();
         for (LineSeg strip : myLineSegments) {
            pnt.set (strip.coords0[0], strip.coords0[1], strip.coords0[2]);
            pnt.updateBounds (pmin, pmax);
            pnt.set (strip.coords1[0], strip.coords1[1], strip.coords1[2]);
            pnt.updateBounds (pmin, pmax);
         }
      }
   }

   protected void accumulateImpulses (
      Map<Vertex3d,Vector3d> map, ContactPoint cpnt, Vector3d nrml, double lam) {
      Vertex3d[] vtxs = cpnt.getVertices();
      double[] wgts = cpnt.getWeights();
      for (int i=0; i<vtxs.length; i++) {
         Vector3d imp = map.get(vtxs[i]);
         if (imp == null) {
            imp = new Vector3d();
            map.put (vtxs[i], imp);
         }
         imp.scaledAdd (lam*wgts[i], nrml);
      }
   }
   
   public Map<Vertex3d,Vector3d> getContactImpulses(CollidableBody colA) {
      LinkedHashMap<Vertex3d,Vector3d> map =
         new LinkedHashMap<Vertex3d,Vector3d>();

      // add impulses associated with vertices on colA. These will arise from
      // contact constraints in both myBilaterals0 and myBilaterals1. The
      // associated vertices are stored either in cpnt0 or cpnt1.  For
      // myBilaterals0, cpnt0 and cpnt1 store the vertices associated
      // myCollidable0 and myCollidable1, respectively. The reverse is true for
      // myBilaterals1. Cpnt0 or cpnt1 are then used depending on whether col
      // equals myCollidable0 or myCollidable1. When cpnt1 is used, the scalar
      // impulse is negated since in that case the normal is oriented for the
      // opposite body.
      for (ContactConstraint c : myBilaterals0.values()) {
         if (colA == myCollidable0) {
            accumulateImpulses (
               map, c.myCpnt0, c.getNormal(), c.getImpulse());
         }
         else {
            accumulateImpulses (
               map, c.myCpnt1, c.getNormal(), -c.getImpulse());
         }
      }
      for (ContactConstraint c : myBilaterals1.values()) {
         if (colA == myCollidable0) {
            accumulateImpulses (
               map, c.myCpnt1, c.getNormal(), -c.getImpulse());
         }
         else {
            accumulateImpulses (
               map, c.myCpnt0, c.getNormal(), c.getImpulse());
         }
      }
      return map;
   }

   /**
    * Get most recent ContactInfo info, for rendering purposes. If no collision
    * occured, this may be null.
    */
   public synchronized ContactInfo getRenderContactInfo() {
      return myRenderContactInfo;
   }

   /* ===== End Render methods ===== */
   
}
