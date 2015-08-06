package com.ikanow.aleph2.management_db.data_model;

import java.io.Serializable;
import java.util.Date;

import akka.actor.ActorRef;

import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.distributed_services.data_model.IRoundRobinEventBusWrapper;

/** Set of ADTs for passing about management information related to buckets
 *  (ADT-ness may not matter in this case, not clear there's ever going to be a bus that handles many different types)
 * @author Alex
 */
public class BucketMgmtMessage implements Serializable {
	private static final long serialVersionUID = -6388837686470436299L;
	protected BucketMgmtMessage() {} // (for bean template utils)
	private BucketMgmtMessage(final DataBucketBean bucket) { this.bucket = bucket;  }
	public DataBucketBean bucket() { return bucket; };
	private DataBucketBean bucket;

	/** An internal class used to wrap event bus publications
	 * @author acp
	 */
	public static class BucketActionEventBusWrapper implements IRoundRobinEventBusWrapper<BucketMgmtMessage>,Serializable {
		private static final long serialVersionUID = -7333589171293704873L;
		protected BucketActionEventBusWrapper() { }
		/** User c'tor for wrapping a BucketActionMessage to be sent over the bus
		 * @param sender - the sender of the message
		 * @param message - the message to be wrapped
		 */
		public BucketActionEventBusWrapper(final ActorRef sender, final BucketMgmtMessage message) {
			this.sender = sender;
			this.message = message;
		}	
		@Override
		public ActorRef sender() { return sender; };
		@Override
		public BucketMgmtMessage message() { return message; };
		
		protected ActorRef sender;
		protected BucketMgmtMessage message;
	}		
	
	/** When a bucket is deleted by the user, this message is queued for a separate thread to delete the actual data and clean the bucket up (which can take some considerable time)
	 * @author Alex
	 */
	public static class BucketDeletionMessage extends BucketMgmtMessage implements Serializable {
		private static final long serialVersionUID = 8418826676589517525L;
		/** (Jackson c'tor)
		 */
		protected BucketDeletionMessage() { super(null); } 
		
		/** User constructor
		 * @param bucket - bucket to delete
		 * @param delete_on - when to delete it
		 */
		public BucketDeletionMessage(final DataBucketBean bucket, final Date delete_on) {
			super(bucket);
			this.delete_on = delete_on;
			//(_id generated by the underlying data store)
			deletion_attempts = 0;
		}

		/** The _id of the object in the underlying data store (so it can be deleted)
		 * @return
		 */
		public Object _id() { return _id; }
		/** The date to delete the object
		 * @return
		 */
		public Date delete_on() { return delete_on; }
		/** The number of (likely failed) attempts to delete the data
		 * @return
		 */
		public Integer deletion_attempts() { return deletion_attempts; }
		
		private Object _id; // (read-only used for deletion)
		private Date delete_on;
		private Integer deletion_attempts; 
	}	
}
