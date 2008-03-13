package com.jbidwatcher.auction;
/*
 * Copyright (c) 2000-2007, CyberFOX Software, Inc. All Rights Reserved.
 *
 * Developed by mrs (Morgan Schweers)
 */

import com.jbidwatcher.util.Constants;
import com.jbidwatcher.util.Currency;
import com.jbidwatcher.util.StringTools;
import com.jbidwatcher.auction.server.AuctionServer;
import com.jbidwatcher.auction.server.AuctionServerManager;
import com.jbidwatcher.auction.event.EventLogger;
import com.jbidwatcher.util.config.*;
import com.jbidwatcher.util.config.ErrorManagement;
import com.jbidwatcher.util.queue.AuctionQObject;
import com.jbidwatcher.util.queue.MQFactory;
import com.jbidwatcher.util.db.ActiveRecord;
import com.jbidwatcher.util.db.Table;
import com.jbidwatcher.util.xml.XMLElement;

import java.io.FileNotFoundException;
import java.text.MessageFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * @brief Contains all the methods to examine, control, and command a
 * specific auction.
 *
 * Where the AuctionInfo class contains information which is purely
 * retrieved from the server, the AuctionEntry class decorates that
 * with things like when it was last updated, whether to snipe, any
 * comment the user might have made on it, etc.
 *
 * I.e. AuctionEntry keeps track of things that the PROGRAM needs to
 * know about the auction, not things that are inherent to auctions.
 *
 * This is not descended from AuctionInfo because the actual type of
 * AuctionInfo varies per server.
 *
 * @author Morgan Schweers
 * @see AuctionInfo
 * @see SpecificAuction
 */
public class AuctionEntry extends ActiveRecord implements Comparable {
  public static final int SUCC_HIGHBID=0, SUCC_OUTBID=1, FAIL_ENDED=2;
  public static final int FAIL_CONNECT=3, FAIL_PARSE=4, FAIL_BADMULTI=5;
  private Category mCategory;

  /**
   * @brief Set a status message, and mark that the connection is currently invalid.
   */
  public void logError() {
    setLastStatus("Communications failure talking to the server.");
    setInvalid();
  }

  public static class AuctionComparator implements Comparator<AuctionEntry>
  {
    /**
     * @param o1 - The first auction entry.
     * @param o2 - The second auction entry.
     * @return -1 if o1 < o2, 0 if o1 == o2, 1 if o1 > o2.
     * @brief Compare two auction objects for ordering by end-date.
     */
    public int compare(AuctionEntry o1, AuctionEntry o2) {
      if (o1 == null && o2 == null) return 0;
      if (o1 == null) return -1;
      if (o2 == null) return 1;

      int result = o1.getEndDate().compareTo(o2.getEndDate());
      if (result == 0) {
        result = o1.compareTo(o2);
      }
      return result;
    }
  }

  /** All the auction-independant information like high bidder's name,
   * seller's name, etc...  This is directly queried when this object
   * is queried about any of those fields.
   *
   */
  private AuctionInfo mAuction = null;

  /**
   * A logging class for keeping track of events.
   *
   * @see com.jbidwatcher.auction.event.EventLogger
   */
  private EventLogger mEntryEvents = null;

  /**
   * Are we in the middle of updating?  This should probably be
   * synchronized, and therefore a Boolean.  BUGBUG -- mrs: 01-January-2003 23:59
   */
  private boolean mUpdating =false;

  /**
   * Is it time to update this AuctionEntry?  This is used for things
   * like sniping, where we want an immediate update afterwards.
   */
  private boolean mNeedsUpdate =false;

  /**
   * Force an update despite ended status, for the post-end update,
   * and for user-initiated updates of ended auctions.
   */
  private boolean mForceUpdate =false;

  /**
   * Have we ever obtained this auction data from the server?
   */
  private boolean mLoaded =false;

  /**
   * If this auction is part of a multiple-snipe, this value will not
   * be null, and will point to a MultiSnipe object.
   */
  private MultiSnipe mMultiSnipe =null;

  /**
   * Is the current user the high bidder?  This is a tougher problem
   * for servers where the user is multiple users, so it hasn't been
   * addressed yet.  FUTURE FEATURE -- mrs: 02-January-2003 00:15
   */
  private boolean mHighBidder =false;

  /**
   * Is the current user the seller?  Same caveats as mHighBidder.
   */
  private boolean mSeller =false;

  /**
   * What is the maximum amount the user bid on the last time they bid?
   */
  private Currency mBid = Currency.NoValue();

  /**
   * How much is the snipe set for, if anything.  This is also used to
   * determine if a snipe is set at all for this auction.
   */
  private Currency mSnipe = Currency.NoValue();

  /**
   * How much was a cancelled snipe for?  (Recordkeeping)
   */
  private Currency mCancelSnipeBid = null;

  /**
   * How many items were bid on the last time the user bid?
   */
  private int mBidQuantity =1;

  /**
   * How many items are to be sniped on, when the snipe fires?
   */
  private int mSnipeQuantity =1;

  /**
   * How many items are to be sniped on, but were cancelled?
   */
  private int mCancelSnipeQuant =1;

  /**
   * What AuctionServer is responsible for handling this
   * AuctionEntry's actions?
   */
  private AuctionServer mServer =null;

  /**
   * The last time this auction was bid on.  Not presently used,
   * although set, saved, and loaded consistently.
   */
  private long mBidAt =0;

  /**
   * The last time this auction was updated from the server.
   */
  private long mLastUpdatedAt =0;

  /**
   * Starting mQuickerUpdateStart milliseconds from the end of the
   * auction, it will start triggering an update of the auction from
   * the server once every minute.  Currently set so that at half an
   * hour from the end of the auction, start updating every minute.
   */
  private long mQuickerUpdateStart = Constants.THIRTY_MINUTES;

  /**
   * Every mUpdateFrequency milliseconds it will trigger an update of
   * the auction from the server.
   */
  private long mUpdateFrequency = Constants.FORTY_MINUTES;

  /**
   * Delta in time from the end of the auction that sniping will
   * occur at.  It's possible to set a different snipe time for each
   * auction, although it's not presently implemented through any UI.
   */
  private long mSnipeAt = -1;

  /**
   * Default delta in time from the end of the auction that sniping
   * will occur at.  This valus can be read and modified by
   * getDefaultSnipeTime() & setDefaultSnipeTime().
   */
  private static long sDefaultSnipeAt = Constants.THIRTY_SECONDS;

  /**
   * The time at which this will cease being a 'recently added'
   * auction.  Usually set to five minutes after the construction.
   */
  private long mAddedRecently = 0;

  /**
   * The time at which this wll cease being paused for update.  This
   * allows the 'Stop' button to work properly.
   */
  private long mDontUpdate = 0;

  private StringBuffer mLastErrorPage = null;

  /**
   * Does all the jobs of the constructors, so that the constructors
   * become simple calls to this function.  Presets up all the
   * necessary variables, loads any data in, sets the lastUpdated
   * flag, all the timers, retrieves the auction if necessary.
   *
   * @param auctionIdentifier - Each auction site has an identifier that
   *                            is used to key the auction.
   */
  private synchronized void prepareAuctionEntry(String auctionIdentifier) {
    mLastUpdatedAt = 0;
    mNeedsUpdate = true;

    // We handle URL identifiers differently than just an auction
    // identifier, so we can create an AuctionEntry with either.
    if(auctionIdentifier.startsWith("http")) {
      mServer = AuctionServerManager.getInstance().getServerForUrlString(auctionIdentifier);
      if(mServer != null) {
        String id = mServer.extractIdentifierFromURLString(auctionIdentifier);
        mAuction = mServer.createAuction(id);

        mNeedsUpdate = false;
      }
    } else {
      mServer = AuctionServerManager.getInstance().getServerForIdentifier(auctionIdentifier);

      if(mServer != null) {
        mAuction = mServer.createAuction(auctionIdentifier);
      }
    }

    mLoaded = mAuction != null;

    /**
     * Note that a bad auction (couldn't get an auction server, or a
     * specific auction info object) doesn't have an identifier, and
     * isn't loaded.  This will fail out the init process, and this
     * will never be added to the items list.
     */
    if (mLoaded) {
      checkHighBidder(true);
      checkSeller();
      checkEnded();
    }
  }

  ///////////////
  //  Constructor

  /** Construct an AuctionEntry from just the ID, loading all necessary info
   * from the server.
   *
   * @param auctionIdentifier The auction ID, from which the entire
   *     AuctionEntry is built by loading data from the server.
   */
  private AuctionEntry(String auctionIdentifier) {
    checkConfigurationSnipeTime();
    mAddedRecently = System.currentTimeMillis() + 5 * Constants.ONE_MINUTE;
    prepareAuctionEntry(auctionIdentifier);
  }

  public static AuctionEntry buildEntry(String auctionIdentifier) {
    AuctionEntry ae = new AuctionEntry(auctionIdentifier);
    if(ae.isLoaded()) {
      String id = ae.saveDB();
      if (id != null) {
        cache(ae.getClass(), "id", id, ae);
        return ae;
      }
    }
    return null;
  }

  /**
   * A constructor that does absolutely nothing.  This is to be used
   * for loading from XML data later on, where the fromXML function
   * will fill out all the internal information.
   */
  public AuctionEntry() {
    checkConfigurationSnipeTime();
  }

  /**
   * @brief Look up to see if the auction is ended yet, just sets
   * mComplete if it is.
   */
  private void checkEnded() {
    if(!isComplete()) {
      Date serverTime = new Date(System.currentTimeMillis() +
                                 mServer.getServerTimeDelta());

      //  If we're past the end time, update once, and never again.
      if(serverTime.after(getEndDate())) {
        setComplete(true);
      }
    }
  }

  /////////////
  //  Accessors

  /**
   * @brief Return the server associated with this entry.
   *
   * @return The server that this auction entry is associated with.
   */
  public AuctionServer getServer() { return(mServer); }
  /**
   * @brief Set the auction server for this entry.
   *
   * This is solely used when serializing in.
   *
   * @param newServer - The server to associate with this auction entry.
   */
  public void setServer(AuctionServer newServer) { mServer = newServer; }

  /**
   * @brief Query whether this entry has ever been loaded from the server.
   *
   * Really shouldn't be necessary, but is.  If we try to create an
   * AuctionEntry with a bad identifier, that doesn't match any
   * server, or isn't 'live' on the auction server, we need an error
   * of this sort, to identify that the load failed.  This is mainly
   * because constructors don't fail.
   *
   * @return Whether this entry has ever been loaded from the server.
   */
  public boolean isLoaded()    { return(mLoaded); }

  /**
   * @brief Check if the current snipe value would be a valid bid currently.
   *
   * @return true if the current snipe is at least one minimum bid
   * increment over the current high bid.  Returns false otherwise.
   */
  public boolean isSnipeValid() {
    Currency minIncrement = mServer.getMinimumBidIncrement(getCurBid(), getNumBidders());
    Currency nextBid = Currency.NoValue();
    boolean rval = false;

    try {
      nextBid = getCurBid().add(minIncrement);

      if(!getSnipe().less(nextBid)) {
        rval = true;
      }
    } catch(Currency.CurrencyTypeException cte) {
      ErrorManagement.handleException("This should never happen (" + nextBid + ", " + getSnipe() + ")!", cte);
    }

    return rval;
  }

  /**
   * @brief Check if the user has an outstanding snipe on this auction.
   *
   * @return Whether there is a snipe waiting on this auction.
   */
  public boolean isSniped() {
    getMultiSnipe();
    return getSnipe() != null && !getSnipe().isNull();
  }

  /**
   * @brief Check if this auction is part of a snipe group.
   *
   * Multisnipes are snipes where each fires, and if one is successful
   * then it automatically cancels all the rest of the snipes.  This
   * lets users snipe on (say) five auctions, even though they only
   * want one of the items.
   *
   * @return Whether this auction is one of a multisnipe group, where
   * each auction is sniped on until one is won.
   */
  public boolean isMultiSniped()    { return(getMultiSnipe() != null); }

  /**
   * @brief Check if the user has ever placed a bid (or completed
   * snipe) on this auction.
   *
   * @return Whether the user has ever actually submitted a bid to the
   * server for this auction.
   */
  public boolean isBidOn() { return(mBid != null && !mBid.isNull()); }

  /**
   * @brief Check if we are in the midst of updating this auction.
   *
   * Not necessary, as the only place it should be used is internally,
   * but it's now being used by auctionTableModel to identify when a
   * specific item is being updated.  It lets the item # be a nice red,
   * momentarily, while the update happens.
   *
   * @return Whether the update for this auction is in progress.
   */
  public boolean isUpdating()  { return(mUpdating); }

  /**
   * @brief Check if the current user is the high bidder on this
   * auction.
   *
   * This should eventually handle multiple users per server, so that
   * users can have multiple identities per auction site.
   * FUTURE FEATURE -- mrs: 02-January-2003 01:25
   *
   * @return Whether the current user is the high bidder.
   */
  public boolean isHighBidder() { return mHighBidder; }

  /**
   * @brief Check if the current user is the seller for this auction.
   *
   * This should eventually handle multiple users per server, so that
   * users can have multiple identities per auction site.
   * FUTURE FEATURE -- mrs: 02-January-2003 01:25
   *
   * @return Whether the current user is the seller.
   */
  public boolean isSeller() { return mSeller; }

  /**
   * @brief What was the highest amount actually submitted to the
   * server as a bid?
   *
   * With some auction servers, it might be possible to find out how
   * much the user bid, but in general presume this value is only set
   * by bidding through this program, or firing a snipe.
   *
   * @return The highest amount bid through this program.
   */
  public Currency getBid()  { return mBid; }

  /**
   * @brief Set the highest amount actually submitted to the server as a bid.
   *
   * @param highBid - The new high bid value to set for this auction.
   */
  public void setBid(Currency highBid)  {
    if(highBid == null) {
      mBid = Currency.NoValue();
    } else {
      mBid = highBid;
    }
  }

  public void setBidQuantity(int quant) { mBidQuantity = quant; }

  /**
   * @brief What number of items will be sniped for when the snipe is
   * fired?
   *
   * @return The count of items to bid on in the snipe.
   */
  public int getSnipeQuantity() { return mSnipeQuantity; }

  /**
   * @brief What was the most recent number of items actually
   * submitted to the server as part of a bid?
   *
   * @return The count of items bid on the last time a user bid.
   */
  public int getBidQuantity()   { return mBidQuantity; }

  private void setMultiSnipe(String identifier, String bgColor, Currency defaultSnipe, boolean subtractShipping) {
    MultiSnipe ms = MultiSnipe.findFirstBy("identifier", identifier);
    if(ms == null) {
      ms = new MultiSnipe(bgColor, defaultSnipe, Long.parseLong(identifier), subtractShipping);
    }
    ms.saveDB();
    setMultiSnipe(ms);
  }

  /**
   * @brief Set this auction as being part of a multi-snipe set,
   * change the multi-snipe group associated with it, or delete it
   * from it's current multi-snipe set.
   *
   * TODO -- Extract this out, create a SnipeInterface which would be
   * implemented by AuctionEntry.  Multisnipe then operates on
   * SnipeInterface objects, so we don't have the X calls Y, Y calls
   * X interrelationship.
   *
   * @param inMS - The multisnipe to set or change.  If it's 'null',
   * it clears the multisnipe for this entry.
   */
  public void setMultiSnipe(MultiSnipe inMS) {
    //  Shortcut: if no change, leave.
    if(mMultiSnipe != inMS) {
      //  If there was a different MultiSnipe before, remove this from it.
      if(mMultiSnipe != null) {
        mMultiSnipe.remove(this);
      }
      mMultiSnipe = inMS;
      //  If we weren't just deleting, then prepare the new snipe, and
      //  add to the multi-snipe group.
      if(mMultiSnipe != null) {
        if(!isSniped()) {
          prepareSnipe(mMultiSnipe.getSnipeValue(this));
        }
        mMultiSnipe.add(this);
        addMulti(mMultiSnipe);
      }
    }

    if(inMS == null) {
      //  If the multisnipe was null, remove the snipe entirely.
      prepareSnipe(Currency.NoValue(), 0);
      setInteger("multisnipe_id", null);
    } else {
      setInteger("multisnipe_id", inMS.getId());      
    }
  }

  /**
   * @brief Get the default snipe time as configured.
   *
   * @return - The default snipe time from the configuration.  If it's
   * not set, return a standard 30 seconds.
   */
  private static long getGlobalSnipeTime() {
    long snipeTime;

    String strConfigSnipeAt = JConfig.queryConfiguration("snipemilliseconds");
    if(strConfigSnipeAt != null) {
      snipeTime = Long.parseLong(strConfigSnipeAt);
    } else {
      snipeTime = Constants.THIRTY_SECONDS;
    }

    return snipeTime;
  }

  /**
   * @brief Get the multi-snipe object associated with this auction, if it's set as a multi-snipe.
   *
   * @return - A multisnipe object or null if there isn't any multisnipe set.
   */
  public MultiSnipe getMultiSnipe() {
    if(mMultiSnipe != null) return mMultiSnipe;

    Integer id = getInteger("multisnipe_id");
    if(id == null) return null;

    MultiSnipe ms = MultiSnipe.find(id);
    setMultiSnipe(ms);
    return ms;
  }

  /**
   * @brief Check if the configuration has a 'snipemilliseconds'
   * entry, and update the default if it does.
   */
  private void checkConfigurationSnipeTime() {
    sDefaultSnipeAt = getGlobalSnipeTime();
  }

  /**
   * @brief Determine how long before the auction-end is the default
   * snipe set to fire?
   *
   * @return The number of milliseconds prior to auction end that a
   * snipe should fire.
   */
  public static long getDefaultSnipeTime() {
    sDefaultSnipeAt = getGlobalSnipeTime();
    return sDefaultSnipeAt;
  }

  /**
   * @brief Set how long before auctions are complete to fire snipes
   * for any auction using the default snipe timer.
   *
   * @param newSnipeAt - The number of milliseconds prior to the end
   * of auctions that the snipe timer will fire.  Can be overridden by
   * setSnipeTime() on a per-auction basis.
   */
  public static void setDefaultSnipeTime(long newSnipeAt) {
    sDefaultSnipeAt = newSnipeAt;
  }

  /**
   * @brief How close prior to the end is this particular auction
   * going to snipe?
   *
   * @return Number of milliseconds prior to auction close that a
   * snipe will be fired.
   */
  public long getSnipeTime() {
    return hasDefaultSnipeTime()? sDefaultSnipeAt : mSnipeAt;
  }

  /**
   * @brief Is this auction using the standard/default snipe time?
   *
   * @return False if the snipe time for this auction has been specially set.
   */
  public boolean hasDefaultSnipeTime() {
    return(mSnipeAt == -1);
  }

  /**
   * @brief Force this auction to snipe at a non-default time prior to
   * the auction end.
   *
   * @param newSnipeTime The new amount of time prior to the end of
   * this auction to fire a snipe.  Value is in milliseconds.  If the
   * value is set to -1, it will reinstate the default time.
   */
  public void setSnipeTime(long newSnipeTime) {
    mSnipeAt = newSnipeTime;
  }

  /**
   * @brief Get the time when this entry will no longer be considered
   * 'newly added', or null if it's been cleared, or is already past.
   *
   * @return The time at which this entry is no longer new.
   */
  public long getJustAdded() {
    return mAddedRecently;
  }

  /**
   * @brief What is the auction's unique identifier on that server?
   *
   * @return The unique identifier for this auction.
   */
  public String getIdentifier() { if(mAuction == null) return null; else return mAuction.getIdentifier(); }

  ///////////////////////////
  //  Actual logic functions

  /**
   * @brief On update, we check if we're the high bidder.
   *
   * When you change user ID's, you should force a complete update, so
   * this is synchronized correctly.
   *
   * @param doNetworkCheck - Should we actually check over the network for new bid information, if the user is outbid?
   */
  private void checkHighBidder(boolean doNetworkCheck) {
    int numBidders = getNumBidders();

    if(numBidders > 0) {
      //  TODO -- This is silly.  Why should the AuctionEntry know about doing a network check?
      if(isOutbid() && doNetworkCheck) {
        mServer.updateHighBid(this);
      }
      if(isBidOn() && isPrivate()) {
        Currency curBid = getCurBid();
        try {
          if(curBid.less(mBid)) mHighBidder = true;
        } catch(Currency.CurrencyTypeException cte) {
          /* Should never happen...?  */
          ErrorManagement.handleException("This should never happen (bad Currency at this point!).", cte);
        }
        if(curBid.equals(mBid)) {
          mHighBidder = numBidders == 1;
          //  mHighBidder == false means that there are multiple bidders, and the price that
          //  two (this user, and one other) bid are exactly the same.  How
          //  do we know who's first, given that it's a private auction?
          //
          //  The only answer I have is to presume that we're NOT first.
          //  eBay knows the 'true' answer, but how to extract it from them...
        }
      } else {
        if(!isDutch()) {
          mHighBidder = mServer.sameUser(getHighBidder());
        }
      }
    }
  }

  /**
   * @brief Determine if we're a high bidder on a multi-item ('dutch')
   * auction.
   */
  private void checkDutchHighBidder() {
    mHighBidder = mServer.isHighDutch(this);
  }

  /**
   * @brief Set the flags if the current user is the seller in this auction.
   */
  private void checkSeller() {
    mSeller = mServer.sameUser(getSeller());
  }

  ////////////////////////////
  //  Periodic logic functions

  /**
   * @brief Determine if it's time to update this auction.
   *
   * PMD bitches long and hard about assigning to null repeatedly in
   * this function.  Any way to clean that up? -- mrs: 23-February-2003 22:28
   *
   * @return Whether or not it's time to retrieve the updated state of
   * this auction.
   */
  public synchronized boolean checkUpdate() {
    long curTime = System.currentTimeMillis();
    if(mAddedRecently != 0) {
      if(curTime > mAddedRecently) mAddedRecently = 0;
    }

    if(mDontUpdate != 0) {
      if(curTime > mDontUpdate) {
        mDontUpdate = 0;
      } else {
        return false;
      }
    }

    if(!mNeedsUpdate) {
      if(!mUpdating && !isComplete()) {
        long serverTime = curTime + mServer.getServerTimeDelta();

        //  If we're past the end time, update once, and never again.
        if(serverTime > getEndDate().getTime()) {
          mNeedsUpdate = true;
        } else {
          if( mUpdateFrequency != Constants.ONE_MINUTE ) {
            if( (getEndDate().getTime() - mQuickerUpdateStart) < serverTime) {
              mUpdateFrequency = Constants.ONE_MINUTE;
              mNeedsUpdate = true;
            }
          }
          if( (mLastUpdatedAt + mUpdateFrequency) < curTime) {
            mNeedsUpdate = true;
          }
        }
      }
    }

    return mNeedsUpdate;
  }

  /**
   * @brief Get the next update time.
   *
   * @return The last time it was updated, plus the update frequency.
   */
  public long getNextUpdate() { return ((mLastUpdatedAt ==0)?System.currentTimeMillis(): mLastUpdatedAt) + mUpdateFrequency; }

  /**
   * @brief Mark this entry as being not-invalid.
   */
  public void clearInvalid() {
    setBoolean("invalid", false);
  }

  /**
   * @brief Mark this entry as being invalid for some reason.
   */
  public void setInvalid() {
    setBoolean("invalid", true);
  }

  /**
   * @brief Is this entry invalid for any reason?
   *
   * Is the data reasonably synchronized with the server?  (When the
   * site stops providing the data, or an error occurs when retrieving
   * this auction, this will be true.)
   *
   * @return - True if this auction is considered invalid, false if it's okay.
   */
  public boolean isInvalid() {
    return getBoolean("invalid", false);
  }

  /**
   * @brief Store a user-specified comment about this item.
   * Allow the user to add a personal comment about this auction.
   *
   * @param newComment - The comment to keep track of.  If it's empty,
   * we effectively delete the comment.
   */
  public void setComment(String newComment) {
    if(newComment.trim().length() == 0)
      setString("comment", null);
    else
      setString("comment", newComment.trim());
  }

  /**
   * @brief Get any user-specified comment regarding this auction.
   *
   * @return Any comment the user may have stored about this item.
   */
  public String getComment() {
    return getString("comment");
  }

  /**
   * @brief Add an auction-specific status message into its own event log.
   *
   * @param inStatus - A string that explains what the event is.
   */
  public void setLastStatus(String inStatus) {
    if(mEntryEvents == null) mEntryEvents = new EventLogger(getIdentifier(), getTitle());
    mEntryEvents.setLastStatus(inStatus);
  }

  public void setShipping(Currency newShipping) {
    setMonetary("shipping", newShipping);
  }

  /**
   * @brief Get a plain version of the event list, where each line is
   * a seperate event, including the title and identifier.
   *
   * @return A string with all the event information included.
   */
  public String getLastStatus() { return getLastStatus(false); }
  /**
   * @brief Get either a plain version of the events, or a complex
   * (bulk) version which doesn't include the title and identifier,
   * since those are set by the AuctionEntry itself, and are based
   * on its own data.
   *
   * @param bulk - Whether to use the plain version (false) or the
   * minimized version (true).
   *
   * @return A string with all the event information included.
   */
  public String getLastStatus(boolean bulk) {
    if(mEntryEvents != null) return mEntryEvents.getLastStatus(bulk);
    return "";
  }

  public int getStatusCount() {
    return mEntryEvents.getStatusCount();
  }
  //////////////////////////
  //  XML Handling functions

  protected String[] infoTags = { "info", "bid", "snipe", "complete", "invalid", "comment", "log", "multisnipe", "shipping", "category" };
  protected String[] getTags() { return infoTags; }

  /**
   * @brief XML load-handling.  It would be really nice to be able to
   * abstract this for all the classes that serialize to XML.
   *
   * @param tagId - The index into 'entryTags' for the current tag.
   * @param curElement - The current XML element that we're loading from.
   */
  protected void handleTag(int tagId, XMLElement curElement) {
    switch(tagId) {
      case 0:  //  Get the general auction information
        mAuction.fromXML(curElement);
        break;
      case 1:  //  Get bid info
        Currency bidAmount = Currency.getCurrency(curElement.getProperty("CURRENCY"),
                                          curElement.getProperty("PRICE"));
        setBid(bidAmount);
        mBidQuantity = Integer.parseInt(curElement.getProperty("QUANTITY"));
        if(curElement.getProperty("WHEN", null) != null) {
          mBidAt = Long.parseLong(curElement.getProperty("WHEN"));
        }
        break;
      case 2:  //  Get the snipe info together
        Currency snipeAmount = Currency.getCurrency(curElement.getProperty("CURRENCY"),
                                            curElement.getProperty("PRICE"));
        prepareSnipe(snipeAmount, Integer.parseInt(curElement.getProperty("QUANTITY")));
        mSnipeAt = Long.parseLong(curElement.getProperty("SECONDSPRIOR"));
        break;
      case 3:
        setComplete(true);
        break;
      case 4:
        setInvalid();
        break;
      case 5:
        setComment(curElement.getContents());
        break;
      case 6:
        mEntryEvents = new EventLogger(getIdentifier(), getTitle());
        mEntryEvents.fromXML(curElement);
        break;
      case 7:
        String identifier = curElement.getProperty("ID");
        String bgColor = curElement.getProperty("COLOR");
        Currency defaultSnipe = Currency.getCurrency(curElement.getProperty("DEFAULT"));
        boolean subtractShipping = curElement.getProperty("SUBTRACTSHIPPING", "false").equals("true");

        setMultiSnipe(identifier, bgColor, defaultSnipe, subtractShipping);
        break;
      case 8:
        Currency shipping = Currency.getCurrency(curElement.getProperty("CURRENCY"),
                                         curElement.getProperty("PRICE"));
        setShipping(shipping);
        break;
      case 9:
        setCategory(curElement.getContents());
        setSticky(curElement.getProperty("STICKY", "false").equals("true"));
        break;
      default:
        break;
        // commented out for FORWARDS compatibility.
        //        throw new RuntimeException("Unexpected value when handling AuctionEntry tags!");
    }
  }

  /**
   * @brief Check everything and build an XML element that contains as
   * children all of the values that need storing for this item.
   *
   * This would be so much more useful if it were 'standard'.
   *
   * @return An XMLElement containing as children, all of the key
   * values associated with this auction entry.
   */
  public XMLElement toXML() {
    XMLElement xmlResult = new XMLElement("auction");

    xmlResult.setProperty("id", getIdentifier());
    xmlResult.addChild(mAuction.toXML());

    if(isBidOn()) {
      XMLElement xbid = new XMLElement("bid");
      xbid.setEmpty();
      xbid.setProperty("quantity", Integer.toString(mBidQuantity));
      xbid.setProperty("currency", mBid.fullCurrencyName());
      xbid.setProperty("price", Double.toString(mBid.getValue()));
      if(mBidAt != 0) {
        xbid.setProperty("when", Long.toString(mBidAt));
      }
      xmlResult.addChild(xbid);
    }

    if(isSniped()) {
      XMLElement xsnipe = new XMLElement("snipe");
      xsnipe.setEmpty();
      xsnipe.setProperty("quantity", Integer.toString(mSnipeQuantity));
      xsnipe.setProperty("currency", getSnipe().fullCurrencyName());
      xsnipe.setProperty("price", Double.toString(getSnipe().getValue()));
      xsnipe.setProperty("secondsprior", Long.toString(mSnipeAt));
      xmlResult.addChild(xsnipe);
    }

    if(isMultiSniped()) {
      MultiSnipe outMS = getMultiSnipe();

      XMLElement xmulti = new XMLElement("multisnipe");
      xmulti.setEmpty();
      xmulti.setProperty("subtractshipping", Boolean.toString(outMS.subtractShipping()));
      xmulti.setProperty("color", outMS.getColorString());
      xmulti.setProperty("default", outMS.getSnipeValue(null).fullCurrency());
      xmulti.setProperty("id", Long.toString(outMS.getIdentifier()));
      xmlResult.addChild(xmulti);
    }

    if(isComplete()) {
      XMLElement xcomplete = new XMLElement("complete");
      xcomplete.setEmpty();
      xmlResult.addChild(xcomplete);
    }

    if(isInvalid()) {
      XMLElement xinvalid = new XMLElement("invalid");
      xinvalid.setEmpty();
      xmlResult.addChild(xinvalid);
    }

    if(getComment() != null) {
      XMLElement xcomment = new XMLElement("comment");
      xcomment.setContents(getComment());
      xmlResult.addChild(xcomment);
    }

    if(getCategory() != null) {
      XMLElement xcategory = new XMLElement("category");
      xcategory.setContents(getCategory());
      xcategory.setProperty("sticky", isSticky() ?"true":"false");
      xmlResult.addChild(xcategory);
    }

    if(getShipping() != null) {
      XMLElement xshipping = new XMLElement("shipping");
      xshipping.setEmpty();
      xshipping.setProperty("currency", getShipping().fullCurrencyName());
      xshipping.setProperty("price", Double.toString(getShipping().getValue()));
      xmlResult.addChild(xshipping);
    }

    if(mEntryEvents != null) {
      XMLElement xlog = mEntryEvents.toXML();
      if (xlog != null) {
        xmlResult.addChild(xlog);
      }
    }
    return xmlResult;
  }

  /**
   * @brief Load auction entries from an XML element.
   *
   * @param inXML - The XMLElement that contains the items to load.
   */
  public void fromXML(XMLElement inXML) {
    String inID = inXML.getProperty("ID", null);
    if(inID != null) {
      mAuction = mServer.getNewSpecificAuction();
      mAuction.setIdentifier(inID);

      super.fromXML(inXML);

      mLoaded = false;

      mLastUpdatedAt = 0;

      if(!isComplete()) setNeedsUpdate();

      if(mEntryEvents == null) {
        mEntryEvents = new EventLogger(getIdentifier(), getTitle());
      }
      checkHighBidder(false);
      checkSeller();
    }
  }

  ////////////////////////////////
  //  Multisnipe utility functions

  private static Map<Long, MultiSnipe> allMultiSnipes = new TreeMap<Long, MultiSnipe>();

  /**
   * @brief Add a new multisnipe to the AuctionEntry class's list of
   * multisnipes.
   *
   * This keeps track of ALL multisnipes, so that they can be
   * loaded/saved okay, as well as checked to remove.
   *
   * @param newMS - The newly created multisnipe to add.
   */
  private void addMulti(MultiSnipe newMS) {
    long newId = newMS.getIdentifier();

    if(!allMultiSnipes.containsKey(newId)) {
      allMultiSnipes.put(newId, newMS);
    }
  }

  /**
   * @brief Figure out what Multisnipe is associated with a given 'id'.
   *
   * @param id - The unique ID's associated with all multisnipes.
   *
   * @return - A multisnipe (or null if none found) that is associated
   * with the passed in Multisnipe ID.
   */
  private MultiSnipe whichMulti(long id) {
    return allMultiSnipes.get(id);
  }

  /////////////////////
  //  Sniping functions

  /**
   * @brief Determine if it is time to trigger this auctions snipe or not.
   *
   * @return True if it is time to snipe, false otherwise.
   */
  public boolean checkSnipe() {
    boolean shouldSnipe = false;

    if(isSniped()) {
      long endDate = mAuction.getEndDate().getTime();
      long curDate = mServer.getAdjustedTime();

      //  If the auction hasn't ended already...
      if(endDate > curDate) {
        //  mSnipeAt / 1000 seconds before the end of the auction.
        long adjustedDate;
        if(hasDefaultSnipeTime()) {
          adjustedDate = curDate + sDefaultSnipeAt;
        } else {
          adjustedDate = curDate + mSnipeAt;
        }

        shouldSnipe = (adjustedDate >= endDate);
      } else {
        setLastStatus("Cancelling snipe, time is suddenly past auction-end.");
        cancelSnipe(true);
      }
    }

    return shouldSnipe;
  }

  /**
   * @brief Return whether this entry ever had a snipe cancelled or not.
   *
   * @return - true if a snipe was cancelled, false otherwise.
   */
  public boolean snipeCancelled() { return mCancelSnipeBid != null; }

  /**
   * @brief Return the amount that the snipe bid was for, before it
   * was cancelled.
   *
   * @return - A currency amount that was set to snipe, but cancelled.
   */
  public Currency getCancelledSnipe() { return mCancelSnipeBid; }

  /**
   * @brief Return the quantity that the snipe bid was for, before it
   * was cancelled.
   *
   * @return - A number of items (for dutch only) that were to be bid on.
   */
  public int getCancelledSnipeQuantity() { return mCancelSnipeQuant; }

  /**
   * @brief Stop any snipe prepared on this auction.  If the auction is
   * already completed, then the snipe information is transferred to the
   * the 'cancelled' status.
   *
   * @param after_end - Is this auction already completed?
   */
  public void cancelSnipe(boolean after_end) {
    if(isSniped()) {
      setLastStatus("Cancelling snipe.");
      if(after_end) {
        mCancelSnipeBid = getSnipe();
        mCancelSnipeQuant = mSnipeQuantity;
      }
    }

    setMultiSnipe(null);
  }

  public void snipeCompleted() {
    setBid(getSnipe());
    mBidQuantity = mSnipeQuantity;
    mNeedsUpdate = true;
    setSnipe(Currency.NoValue());
    mSnipeQuantity = 0;
  }

  /**
   * @brief Completely update auction info from the server for this auction.
   */
  public void update() {
    mUpdating =true;
    mNeedsUpdate = false;
    MQFactory.getConcrete("redraw").enqueue(this);
    mForceUpdate = false;

    // We REALLY don't want to leave an auction in the 'updating'
    // state.  It does bad things.
    try {
      mServer.reloadAuction(this);
    } catch(Exception e) {
      ErrorManagement.handleException("Unexpected exception during auction reload/update.", e);
    }
    mLastUpdatedAt = System.currentTimeMillis();
    mUpdating =false;
    MQFactory.getConcrete("redraw").enqueue(this);
    mAddedRecently = 0;
    try {
      checkHighBidder(true);
      if(isDutch()) checkDutchHighBidder();
    } catch(Exception e) {
      ErrorManagement.handleException("Unexpected exception during high bidder check.", e);
    }
    checkSeller();
    if (isComplete()) {
      //  If the auction is really completed now, and it was part of a
      //  multisnipe group, let's check if it's been won.  If it has,
      //  tell the MultiSnipe object that one has been won, so it can
      //  clear out the others!
      if (isMultiSniped()) {
        MultiSnipe ms = getMultiSnipe();
        if (isHighBidder() && (!isReserve() || isReserveMet())) {
          ms.setWonAuction(/* this */);
        } else {
          ms.remove(this);
        }
      }
      if (isSniped()) {
        setLastStatus("Cancelling snipe, auction is reported as ended.");
        cancelSnipe(true);
      }
    } else {
      Date serverTime = new Date(System.currentTimeMillis() +
                                 mServer.getServerTimeDelta());

      //  If we're past the end time, update once, and never again.
      if (serverTime.after(getEndDate())) {
        setComplete(true);
        mNeedsUpdate = true;
        mForceUpdate = true;
      }
    }
  }

  public void prepareSnipe(Currency snipe) { prepareSnipe(snipe, 1); }

  /**
   * @brief Set up the fields necessary for a future snipe.
   *
   * This needs to be enhanced to work with multiple items, and
   * different snipe times.
   *
   * @param snipe The amount of money the user wishes to bid at the last moment.
   * @param quantity The number of items they want to snipe for.
   */
  public void prepareSnipe(Currency snipe, int quantity) {
    setSnipe(snipe);
    mSnipeQuantity = quantity;

    if(snipe == null || snipe.isNull()) {
      MQFactory.getConcrete(mServer.getName()).enqueue(new AuctionQObject(AuctionQObject.CANCEL_SNIPE, this, null));
    } else {
      MQFactory.getConcrete(mServer.getName()).enqueue(new AuctionQObject(AuctionQObject.SET_SNIPE, this, null));
    }
    MQFactory.getConcrete("Swing").enqueue("SNIPECHANGED");
  }

  /** @brief Actually bid on a single item for a given price.
   *
   * Also called by the snipe() function, to actually bid.
   *
   * @param bid - The amount of money to bid on 1 of this item.
   *
   * @return The result of the bid attempt.
   */
  public int bid(Currency bid) {
    return( bid(bid, 1) );
  }

  /**
   * @brief Bid a given price on an arbitrary number of a particular item.
   *
   * @param bid - The amount of money being bid.
   * @param bidQuantity - The number of items being bid on.
   *
   * @return The result of the bid attempt.
   */
  public int bid(Currency bid, int bidQuantity) {
    setBid(bid);
    mBidAt = System.currentTimeMillis();

    ErrorManagement.logDebug("Bidding " + bid + " on " + bidQuantity + " item[s] of (" + getIdentifier() + ")-" + getTitle());

    return(mServer.bid(this, bid, bidQuantity));
  }

  /**
   * @brief Buy an item directly.
   *
   * @param quant - The number of them to buy.
   *
   * @return The result of the 'Buy' attempt.
   */
  public int buy(int quant) {
    Currency bin = getBuyNow();
    if(bin != null && !bin.isNull()) {
      setBid(getBuyNow());
      mBidAt = System.currentTimeMillis();
      ErrorManagement.logDebug("Buying " + quant + " item[s] of (" + getIdentifier() + ")-" + getTitle());
      return mServer.buy(this, quant);
    }
    return AuctionServer.BID_ERROR_NOT_BIN;
  }

  /**
   * @brief This auction entry needs to be updated.
   */
  public void setNeedsUpdate() { mNeedsUpdate = true; }

  /**
   * @brief Make this auction update despite being ended.
   *
   * Clear the 'dont update' flag for this, because this is always a
   * user-forced update message.
   */
  public void forceUpdate() { mForceUpdate = true; mDontUpdate = 0; mNeedsUpdate = true; }

  /**
   * @brief Get the category this belongs in, usually used for tab names, and fitting in search results.
   *
   * @return - A category, or null if none has been assigned.
   */
  public String getCategory() {
    if(mCategory == null) {
      String category_id = get("category_id");
      if(category_id != null) {
        mCategory = Category.findFirstBy("id", category_id);
      }
    }
    return mCategory != null ? mCategory.getName() : null;
  }

  /**
   * @brief Set the category associated with the auction entry.  If the
   * auction is ended, this is automatically considered sticky.
   *
   * @param newCategory - The new category to associate this item with.
   */
  public void setCategory(String newCategory) {
    Category c = Category.findFirstByName(newCategory);
    if(c == null) {
      c = Category.findOrCreateByName(newCategory);
    }
    setInteger("category_id", c.getId());
    mCategory = c;
    if(isComplete()) setSticky(true);
  }

  /**
   * @brief Returns whether or not this auction entry is 'sticky', i.e. sticks to any category it's set to.
   * Whether the 'category' information is sticky (i.e. overrides 'deleted', 'selling', etc.)
   *
   * @return true if the entry is sticky, false otherwise.
   */
  public boolean isSticky() { return getBoolean("sticky"); }

  /**
   * @brief Set the sticky flag on or off.
   *
   * This'll probably be exposed to the user through a right-click context menu, so that people
   * can make auctions not move from their sorted categories when they end.
   *
   * @param beSticky - Whether or not this entry should be sticky.
   */
  public void setSticky(boolean beSticky) { setBoolean("sticky", beSticky); }

  /**
   * @brief This auction entry does NOT need to be updated.
   */
  public void clearNeedsUpdate() {
    mNeedsUpdate = false;
    mLastUpdatedAt = System.currentTimeMillis();
  }

  /**
   * @brief Pause updating this item, including things like moving to
   * completed, etc.
   */
  public void pauseUpdate() {
    mDontUpdate = System.currentTimeMillis() + 5 * Constants.ONE_MINUTE;
  }

  /**
   * @brief Is this entry paused?
   *
   * @return - Whether updates for this item are paused.
   */
  public boolean isPaused() { return mDontUpdate != 0; }

  public static final String endedAuction = "Auction ended.";
  private static final String mf_min_sec = "{6}{2,number,##}m, {7}{3,number,##}s";
  private static final String mf_hrs_min = "{5}{1,number,##}h, {6}{2,number,##}m";
  private static final String mf_day_hrs = "{4}{0,number,##}d, {5}{1,number,##}h";

  private static final String mf_min_sec_detailed = "{6}{2,number,##} minute{2,choice,0#, |1#, |1<s,} {7}{3,number,##} second{3,choice,0#|1#|1<s}";
  private static final String mf_hrs_min_detailed = "{5}{1,number,##} hour{1,choice,0#, |1#, |1<s,} {6}{2,number,##} minute{2,choice,0#|1#|1<s}";
  private static final String mf_day_hrs_detailed = "{4}{0,number,##} day{0,choice,0#, |1#, |1<s,}  {5}{1,number,##} hour{1,choice,0#|1#|1<s}";

  //0,choice,0#are no files|1#is one file|1<are {0,number,integer} files}

  private static String convertToMsgFormat(String simpleFormat) {
    String msgFmt = simpleFormat.replaceAll("DD", "{4}{0,number,##}");
    msgFmt = msgFmt.replaceAll("HH", "{5}{1,number,##}");
    msgFmt = msgFmt.replaceAll("MM", "{6}{2,number,##}");
    msgFmt = msgFmt.replaceAll("SS", "{7}{3,number,##}");

    return msgFmt;
  }

  /**
   * @brief Determine the amount of time left, and format it prettily.
   *
   * @return A nicely formatted string showing how much time is left
   * in this auction.
   */
  public String getTimeLeft() {
    long rightNow = System.currentTimeMillis();
    long officialDelta = mServer.getServerTimeDelta();
    long pageReqTime = mServer.getPageRequestTime();
    boolean use_detailed = JConfig.queryConfiguration("timeleft.detailed", "false").equals("true");

    if(!isComplete()) {
      long dateDiff;
      try {
        dateDiff = getEndDate().getTime() - ((rightNow + officialDelta) - pageReqTime);
      } catch(Exception endDateException) {
        ErrorManagement.handleException("Error getting the end date.", endDateException);
        dateDiff = 0;
      }

      if(dateDiff > Constants.ONE_DAY * 60) return "N/A";

      if(dateDiff >= 0) {
        long days = dateDiff / (Constants.ONE_DAY);
        dateDiff -= days * (Constants.ONE_DAY);
        long hours = dateDiff / (Constants.ONE_HOUR);
        dateDiff -= hours * (Constants.ONE_HOUR);
        long minutes = dateDiff / (Constants.ONE_MINUTE);
        dateDiff -= minutes * (Constants.ONE_MINUTE);
        long seconds = dateDiff / Constants.ONE_SECOND;
        String mf;

        String cfg;
        if(days == 0) {
          if(hours == 0) {
            mf = use_detailed?mf_min_sec_detailed:mf_min_sec;
            cfg = JConfig.queryConfiguration("timeleft.minutes");
            if(cfg != null) mf = convertToMsgFormat(cfg);
          } else {
            mf = use_detailed?mf_hrs_min_detailed:mf_hrs_min;
            cfg = JConfig.queryConfiguration("timeleft.hours");
            if (cfg != null) mf = convertToMsgFormat(cfg);
          }
        } else {
          mf = use_detailed?mf_day_hrs_detailed:mf_day_hrs;
          cfg = JConfig.queryConfiguration("timeleft.days");
          if (cfg != null) mf = convertToMsgFormat(cfg);
        }
        String dpad="", hpad="", mpad="", spad="";
        if(days < 10) dpad = " ";
        if(hours < 10) hpad = " ";
        if(minutes < 10) mpad = " ";
        if(seconds < 10) spad = " ";

        Object[] timeArgs = { days, hours, minutes, seconds,
                              dpad, hpad,  mpad,    spad };

        return(MessageFormat.format(mf, timeArgs));
      }
    }
    return endedAuction;
  }

  public boolean isUpdateForced() { return mForceUpdate; }

  /**
   * @brief Do a 'standard' compare to another AuctionEntry object.
   *
   * The standard ordering is as follows:
   *    (if identifiers or pointers are equal, entries are equal)
   *    If this end date is after the passed in one, we are greater.
   *    If this end date is before, we are lesser.
   *    Otherwise (EXACTLY equal dates!), order by identifier.
   *
   * @param other - The AuctionEntry to compare to.
   *
   * @return - -1 for lesser, 0 for equal, 1 for greater.
   */
  public int compareTo(Object other) {
    //  We are always greater than null
    if(other == null) return 1;
    //  We are always equal to ourselves
    if(other == this) return 0;
    //  This is an incorrect usage and should be caught.
    if(!(other instanceof AuctionEntry)) throw new ClassCastException("AuctionEntry cannot compareTo different classes!");

    AuctionEntry comparedAuctionEntry = (AuctionEntry) other;

    //  If the identifiers are the same, we're equal.
    if(getIdentifier().equals(comparedAuctionEntry.getIdentifier())) return 0;

    //  If this ends later than the passed in object, then we are 'greater'.
    if(getEndDate().after(comparedAuctionEntry.getEndDate())) return 1;
    if(comparedAuctionEntry.getEndDate().after(getEndDate())) return -1;

    //  Whoops!  Dates are equal, down to the second probably!

    //  Since this ends exactly at the same time as another auction,
    //  check the identifiers (which *must* be different here.
    return getIdentifier().compareTo(comparedAuctionEntry.getIdentifier());
  }

  /**
   * @brief Return a value that indicates the status via bitflags, so that sorted groups by status will show up grouped together.
   *
   * @return - An integer containing a bitfield of relevant status bits.
   */
  public int getFlags() {
    int r_flags = 1;

    if (isFixed()) r_flags = 0;
    if (getHighBidder() != null) {
      if (isHighBidder()) {
        r_flags = 2;
      } else if (isSeller() && getNumBidders() > 0 &&
                 (!isReserve() || isReserveMet())) {
        r_flags = 4;
      }
    }
    if (!getBuyNow().isNull()) {
      r_flags += 8;
    }
    if (isReserve()) {
      if (isReserveMet()) {
        r_flags += 16;
      } else {
        r_flags += 32;
      }
    }
    if(hasPaypal()) r_flags += 64;
    return r_flags;
  }

  ////////////////////////////////////////
  //  Passthrough functions to AuctionInfo

  /**
   * @brief Force this auction to use a particular set of auction
   * information for it's core data (like seller's name, current high
   * bid, etc.).
   *
   * @param inAI - The AuctionInfo object to make the new core data.
   */
  public void setAuctionInfo(AuctionInfo inAI) {
    //  If the end date has changed, let's reschedule the snipes for the new end date...?
    if(mAuction != null && mAuction.getEndDate() != null && mAuction.getEndDate().equals(inAI.getEndDate())) {
      Currency saveSnipeBid = getSnipe();
      int saveSnipeQuantity = mSnipeQuantity;
      prepareSnipe(null);
      prepareSnipe(saveSnipeBid, saveSnipeQuantity);
    }
    mAuction = inAI;
    String auction_id = mAuction.saveDB();
    if(auction_id != null) set("auction_id", auction_id);

    checkHighBidder(false);
    checkSeller();
    checkEnded();
  }

  /* Accessor functions that are passed through directly down
   * to the internal AuctionInfo object.
   */
  public Currency getCurBid() { return mAuction.getCurBid(); }
  public Currency getUSCurBid() { return mAuction.getUSCurBid(); }
  public Currency getMinBid() { return mAuction.getMinBid(); }

  /**
   * @return - Shipping amount, overrides AuctionInfo shipping amount if present.
   */
  public Currency getShipping() {
    if(getMonetary("shipping") != null) return getMonetary("shipping");
    return mAuction.getShipping();
  }
  public Currency getInsurance() { return mAuction.getInsurance(); }
  public boolean getInsuranceOptional() { return mAuction.isInsuranceOptional(); }
  public Currency getBuyNow() { return mAuction.getBuyNow(); }

  public int getQuantity() { return mAuction.getQuantity(); }
  public int getNumBidders() { return mAuction.getNumBidders(); }


  public String getSeller() { return mAuction.getSellerName(); }
  public String getHighBidder() { return mAuction.getHighBidder(); }
  public String getHighBidderEmail() { return mAuction.getHighBidderEmail(); }
  public String getTitle() { return mAuction.getTitle(); }

  public Date getStartDate() {
    if (mAuction != null && mAuction.getStartDate() != null) {
      Date start = mAuction.getStartDate();
      if(start != null) return start;
    }

    return Constants.LONG_AGO;
  }

  public Date getEndDate() {
    if(mAuction != null && mAuction.getEndDate() != null) {
      Date end = mAuction.getEndDate();
      if(end != null) return end;
    }

    return Constants.FAR_FUTURE;
  }
  public Date getSnipeDate() { return new Date(mAuction.getEndDate().getTime() - getSnipeTime()); }

  public boolean isDutch() { return mAuction.isDutch(); }
  public boolean isReserve() { return mAuction.isReserve(); }
  public boolean isReserveMet() { return mAuction.isReserveMet(); }
  public boolean isPrivate() { return mAuction.isPrivate(); }
  public boolean isFixed() { return mAuction.isFixedPrice(); }
  public boolean isOutbid() { return mAuction.isOutbid(); }

  public StringBuffer getContent() { return mAuction.getContent(); }
  public String getThumbnail() { return mAuction.getThumbnail(); }

  public boolean hasPaypal() { return mAuction.hasPaypal(); }
  public String getItemLocation() { return mAuction.getItemLocation(); }
  public String getPositiveFeedbackPercentage() { return mAuction.getPositiveFeedbackPercentage(); }
  public int getFeedbackScore() { return mAuction.getFeedbackScore(); }

  public void setErrorPage(StringBuffer page) { mLastErrorPage = page; }
  public StringBuffer getErrorPage() { return mLastErrorPage; }

  public Currency getShippingWithInsurance() {
    Currency ship = getShipping();
    if(ship == null || ship.isNull())
      return Currency.NoValue();
    else {
      if(getInsurance() != null &&
         !getInsurance().isNull() &&
         !getInsuranceOptional()) {
        try {
          ship = ship.add(getInsurance());
        } catch(Currency.CurrencyTypeException cte) {
          ErrorManagement.handleException("Insurance is somehow a different type than shipping?!?", cte);
        }
      }
    }
    return ship;
  }

  public boolean isShippingOverridden() {
    return getShipping() != null && !getShipping().isNull();
  }

  public String getURL() {
    return mServer.getStringURLFromItem(mAuction.getIdentifier());
  }

  public StringBuffer getBody() throws FileNotFoundException {
    return mServer.getAuction(StringTools.getURLFromString(getURL()));
  }

  /**
   * @return - Has this auction already ended?  We keep track of this, so we
   * don't waste time on it afterwards, even as much as creating a
   * Date object, and comparing.
   */
  public boolean isComplete() { return getBoolean("ended"); }
  public void setComplete(boolean complete) { setBoolean("ended", complete); }

  /**
   * @brief What is the amount that will be sniped when the snipe
   * timer goes off?
   *
   * @return The amount that will be submitted as a bid when it is
   * time to snipe.
   */
  public Currency getSnipe() { return mSnipe; }
  protected void setSnipe(Currency snipe) {
    mSnipe = snipe;
  }

  /*************************/
  /* Database access stuff */
  /*************************/

  public String saveDB() {
    if(mAuction == null) return null;

    String auction_id = mAuction.saveDB();
    if(auction_id != null) set("auction_id", auction_id);

    if(mCategory != null) {
      String category_id = mCategory.saveDB();
      if(category_id != null) set("category_id", category_id);
    }

    String id = super.saveDB();
    set("id", id);
    return id;
  }

  private static Table sDB = null;
  protected static String getTableName() { return "entries"; }
  protected Table getDatabase() {
    if(sDB == null) {
      sDB = openDB(getTableName());
    }
    return sDB;
  }

  public static AuctionEntry findFirstBy(String key, String value) {
    return (AuctionEntry) ActiveRecord.findFirstBy(AuctionEntry.class, key, value);
  }

  public boolean delete() {
    mAuction.delete();
    return super.delete();
  }
}
