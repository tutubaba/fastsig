/**
 * Copyright 2010 Rice University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Scott A. Crosby <scrosby@cs.rice.edu>
 *
 */

package edu.rice.batchsig.lazy;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;

import edu.rice.batchsig.IMessage;
import edu.rice.batchsig.SignaturePrimitives;
import edu.rice.batchsig.VerifyHisttreeCommon;
import edu.rice.batchsig.bench.Tracker;



/**
 * Copy of verifyqueuelazy, which takes loginlogout hints and tries to delay
 * signature verification for spliced signatures.
 * 
 * Messages are only lazily verified, when explicitly forced.
 * 
 * When a public key signature is verified, that verification can validate both
 * that one message, and earlier messages through the transitive closure of
 * verified splices.
 * 
 * Lets say that message M is forced, we could check the signature on M, which
 * will validate M. M might have valid splices to prior messages, spliced to yet other messages.
 * With one public key verification on M, we can validate those prior messages through very cheap hash operations on spliced signatures.
 * 
 * This is not ideal. What would be more efficient would be to look for a later message P whose
 * transitive closure of splices includes M, then by validating P, we validate many more messages through cheap hash operations.
 * 
 * How to find P? 
 * 
 * Algorithm 1: Use Union-Find. 
 * 
 * Each group is a set of messages which can be validated by verifying the exlempar element's single public key signature. 
 * (This means that validate the signature splices BEFORE merging into a group).
 *
 * Given a new message M, the cases are:
 * 
 * If an existing message in a group includes a splice to M, validate the splice and add M to the group.
 * If M includes a splice the exlempar element of a group G, validate the splice and M becomes the exlempar of that group.
 * If M includes a splice to the exlempar elements of several groups, validate the splices and M becomes the exlempar of the union of the groups.
 *
 * PROBLEM: Not robust to signature validation failures.
 *
 * Algorithm 2: Store the entire graph
 *
 * Store a dag. Nodes in this dag correspond to messages, and edges correspond to splice points. Some nodes in this graph can be 'incomplete', meaning that we've not seen.
 * 
 * either actual message, or 'stub' messages. 
 *
 *  NOT multithread safe. 
 * 
 * */



public class VerifyHisttreeLazily extends VerifyHisttreeCommon implements VerifyLazily, WrappedIMessage.Callback {
	public static int MAX_TREES = 1000;
	public static int MAX_TREE_SIZE = 1000;
	/** Map from recipient_user to the messages queued to that recipient_user */
	Multimap<Object,IMessage> userToMessages = HashMultimap.create();
	
	
	public VerifyHisttreeLazily(SignaturePrimitives signer) {
		super(signer);
	}

	AtomicInteger size = new AtomicInteger(0);
	
	/** THis message has been validated, can stop tracking it now. */
	public void messageValidatorCallback(IMessage m, boolean valid) {
		userToMessages.remove(m.getRecipientUser(),m);
		size.decrementAndGet();
	}
		
	/** Map from (author_server, treeid) -> OneTree */
	private Table<Object,Long,OneTree> map1 = HashBasedTable.create();

	/** Track info for expiration */
	private TreeExpirationManager expirationqueue = new TreeExpirationManager(MAX_TREES);

	/** Force everything in these trees */
	private HashSet<OneTree> treesToForceAll = new HashSet<OneTree>();
	/** Force the oldest message in these trees */
	private HashSet<OneTree> treesToForceOne = new HashSet<OneTree>();

	class TreeExpirationManager extends ExpirationManager<OneTree> {
		private static final long serialVersionUID = 1L;

		TreeExpirationManager(int size_limit) {
			super(size_limit);
		}
		
		protected boolean removeEldestEntry(Map.Entry<OneTree,OneTree> eldest) {
			if (super.removeEldestEntry(eldest)) {
				System.out.println("Expiration for too many trees");
				treesToForceAll.add(eldest.getValue());
				return true;
			}
			return false;
		}
	}
	

	OneTree getOneTreeForMessage(IMessage m) {
		return map1.get(m.getAuthor(),m.getSignatureBlob().getTreeId());
	}
	OneTree makeOneTreeForMessage(IMessage m) {
		OneTree out = getOneTreeForMessage(m);
		if (out == null) {
			out = new OneTree(this,m.getAuthor(),m.getSignatureBlob().getTreeId());
			map1.put(m.getAuthor(),m.getSignatureBlob().getTreeId(),out);
		}
		return out;
	}
	
	/** At the end of a batch of inserts, handle expiration forcing */
	public void process() {
		//System.out.println("  Forcing batch begin");
		for (OneTree i : treesToForceAll) {
			i.forceAll();
			map1.remove(i.getAuthor(),i.getTreeid());
		}	
		for (OneTree i : treesToForceOne)
			if (!treesToForceAll.contains(i))
				i.forceOldest();
		treesToForceAll.clear();
		treesToForceOne.clear();
		//System.out.println("  Forcing batch end");
	}
	
	/** Force one message */
	public void force(IMessage m) {
		OneTree tree = getOneTreeForMessage(m);
		if (tree == null) {
			System.out.println("Forcing message thats not in the tree??? Don't do anything.");
			return;
		}
		tree.forceMessage(m);
	}
	
	/** Force all messages by the one user */
	public void forceUser(Object user, long timestamp) {
		while (true) {
			Collection<IMessage> ml = userToMessages.get(user);
			if (ml.isEmpty())
				return;
			IMessage m = ml.iterator().next();
			//System.out.format("Forcing user %s at %d was %d  -- %s\n",user.toString(),timestamp,m.getCreationTime(),m.toString());
			m.resetCreationTimeTo(timestamp);
			//System.out.format("For forced user %s, found message %s\n",user.toString(),m.toString());
			force(m);
		}
	}
	
	public void forceAll() {
		for (OneTree tree : map1.values())
			tree.forceAll();
	}
	
	public void forceOldest() {
		// Keep on trying until we expire an entry, if any exists.
		if (expirationqueue.size() == 0)
			return;
		OneTree x = expirationqueue.keySet().iterator().next();
		Tracker.singleton.idleforces++;
		if (x == null)
			throw new Error("Expiration queue weirdness");
		if (x.forceOldest())
			return;
		// If the onetree is empty, remove this from the expiration queue entirely.
		expirationqueue.remove(x);
	}

	public void add(IMessage m) {
		size.incrementAndGet();
		OneTree tree = this.makeOneTreeForMessage(m);
		tree.addMessage(m);
		if (tree.size() > MAX_TREE_SIZE) {
			treesToForceOne.add(tree);
			System.out.println("Expiration for too big tree.");
		}
		expirationqueue.put(tree,tree);
		userToMessages.put(m.getRecipientUser(), m);
		process();
	}

	/** Get the size of the queue. May be called concurrently from any number of threads */
	public int peekSize() {
		return size.get();
	}
	
}
