/*
 * Copyright (C) 2014 The Android Open Source Project
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
 */

package com.android.internal.telephony.dataconnection;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IState;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.dataconnection.DcSwitchAsyncChannel.ConnectInfo;
import com.android.internal.telephony.dataconnection.DcSwitchAsyncChannel.RequestInfo;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.SubscriptionController;

import android.os.AsyncResult;
import android.os.Message;
import android.telephony.Rlog;
import android.telephony.ServiceState;

public class DcSwitchStateMachine extends StateMachine {
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private static final String LOG_TAG = "DcSwitchSM";

    // ***** Event codes for driving the state machine
    private static final int BASE = Protocol.BASE_DATA_CONNECTION_TRACKER + 0x00001000;
    private static final int EVENT_CONNECTED       = BASE + 0;
    private static final int EVENT_DATA_ALLOWED    = BASE + 1;
    private static final int CMD_RETRY_ATTACH      = BASE + 2;
    private static final int EVENT_DATA_DISALLOWED = BASE + 3;

    private int mId;
    private Phone mPhone;
    private AsyncChannel mAc;

    private Message mResponseMsg = null;

    private IdleState mIdleState = new IdleState();
    private EmergencyState mEmergencyState = new EmergencyState();
    private AttachingState mAttachingState = new AttachingState();
    private DataAllowedState mDataAllowedState = new DataAllowedState();
    private AttachedState mAttachedState = new AttachedState();
    private DetachingState mDetachingState = new DetachingState();
    private DefaultState mDefaultState = new DefaultState();

    // In case of transition to emergency state, this tracks the state of the state machine prior
    // to entering emergency state
    private IState mPreEmergencyState;

    protected DcSwitchStateMachine(Phone phone, String name, int id) {
        super(name);
        if (DBG) log("DcSwitchState constructor E");
        mPhone = phone;
        mId = id;

        addState(mDefaultState);
        addState(mIdleState, mDefaultState);
        addState(mEmergencyState, mDefaultState);
        addState(mAttachingState, mDefaultState);
        addState(mDataAllowedState, mAttachingState);
        addState(mAttachedState, mDefaultState);
        addState(mDetachingState, mDefaultState);
        setInitialState(mIdleState);
        if (DBG) log("DcSwitchState constructor X");
    }

//    public void notifyDataConnection(int phoneId, String state, String reason,
//            String apnName, String apnType, boolean unavailable) {
//        if (phoneId == mId &&
//                TextUtils.equals(state, PhoneConstants.DataState.CONNECTED.toString())) {
//            sendMessage(obtainMessage(EVENT_CONNECTED));
//        }
//    }

    private class IdleState extends State {
        @Override
        public void enter() {
            if (DBG) log("IdleState: enter");
            mResponseMsg = null;
            try {
                DctController.getInstance().processRequests();
            } catch (RuntimeException e) {
                if (DBG) loge("DctController is not ready");
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    ConnectInfo connectInfo = (ConnectInfo)msg.obj;
                    RequestInfo apnRequest = connectInfo.request;
                    mResponseMsg = connectInfo.responseMessage;
                    apnRequest.log("DcSwitchStateMachine.IdleState: REQ_CONNECT");
                    if (DBG) log("IdleState: REQ_CONNECT, apnRequest=" + apnRequest);
                    transitionTo(mAttachingState);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) log("AttachingState: REQ_DISCONNECT_ALL" );

                    // Shouldn't have any requests, but why not try..
                    DctController.getInstance().releaseAllRequests(mId);

                    retVal = HANDLED;
                    break;
                }


                case DcSwitchAsyncChannel.EVENT_DATA_ATTACHED:
                    SubscriptionController subController = SubscriptionController.getInstance();
                    int ddsSubId = subController.getDefaultDataSubId();
                    int ddsPhoneId = subController.getPhoneId(ddsSubId);

                    if (DBG) {
                        log("IdleState: EVENT_DATA_ATTACHED");
                    }

                    if (DctController.getInstance().isDataAllowedOnPhoneId(mId)) {
                        if (DBG) {
                            log("IdleState: DDS sub reported ATTACHed in IDLE state");
                        }
                        /* Move to AttachingState and handle this ATTACH msg over there.
                         * This would ensure that Modem gets a ALLOW_DATA(true)
                         */
                        if (ServiceState.isCdma(dataRat)) {
                            deferMessage(msg);
                            transitionTo(mAttachingState);
                        } else {
                            transitionTo(mAttachedState);
                        }
                    } else {
                        if (DBG) log("IdleState: ignore ATATCHed event as data is not allowed");
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_CONNECTED: {
                    if (DBG) {
                        log("IdleState: Receive invalid event EVENT_CONNECTED!");
                    }
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("IdleState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class EmergencyState extends State {
        @Override
        public boolean processMessage(Message msg) {
            final PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
            if (!pb.mDcTracker.isEmergency()) {
                loge("EmergencyState: isEmergency() is false. deferMessage msg.what=0x" +
                        Integer.toHexString(msg.what));
                deferMessage(msg);
                transitionTo(mPreEmergencyState);
                return HANDLED;
            }

            switch (msg.what) {
                case DcSwitchAsyncChannel.EVENT_EMERGENCY_CALL_ENDED: {
                    transitionTo(mPreEmergencyState);
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_EMERGENCY_CALL_STARTED: {
                    loge("EmergencyState: ignoring EVENT_EMERGENCY_CALL_STARTED");
                    break;
                }

                case DcSwitchAsyncChannel.REQ_IS_IDLE_STATE: {
                    boolean val = mPreEmergencyState == mIdleState;
                    if (VDBG) log("EmergencyState: REQ_IS_IDLE_STATE  isIdle=" + val);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_IS_IDLE_STATE, val ? 1 : 0);
                    break;
                }

                case DcSwitchAsyncChannel.REQ_IS_IDLE_OR_DETACHING_STATE: {
                    boolean val = (mPreEmergencyState == mIdleState ||
                            mPreEmergencyState == mDetachingState);
                    if (VDBG) {
                        log("EmergencyState: " +
                            "REQ_IS_IDLE_OR_DETACHING_STATE  isIdleDetaching=" + val);
                    }
                    mAc.replyToMessage(msg,
                            DcSwitchAsyncChannel.RSP_IS_IDLE_OR_DETACHING_STATE, val ? 1 : 0);
                    break;
                }

                // explicitly call out the messages we must defer
                // anything not listed falls through to the default state
                case DcSwitchAsyncChannel.REQ_CONNECT:
                case DcSwitchAsyncChannel.REQ_RETRY_CONNECT:
                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL:
                case DcSwitchAsyncChannel.EVENT_DATA_ATTACHED:
                case DcSwitchAsyncChannel.EVENT_DATA_DETACHED: {
                    log("EmergencyState: deferMessage msg.what=0x" + Integer.toHexString(msg.what));
                    deferMessage(msg);
                    break;
                }

                default: {
                    if (VDBG) {
                        log("EmergencyState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    return NOT_HANDLED;
                }
            }

            return HANDLED;
        }
    }

    private class AttachingState extends State {
        private int mCurrentAllowedSequence = 0;
        @Override
        public void enter() {
            log("AttachingState: enter");
            doEnter();
        }

        private void doEnter() {
            final PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
            pb.mCi.setDataAllowed(true, obtainMessage(EVENT_DATA_ALLOWED,
                    ++mCurrentAllowedSequence, 0));
            DctController.getInstance().resetDdsSwitchNeededFlag();
            // if we're on a carrier that unattaches us if we're idle for too long
            // (on wifi) and they won't re-attach until we poke them.  Poke them!
            // essentially react as Attached does here in Attaching.
            if (pb.mDcTracker.getAutoAttachOnCreation()) {
                if (DBG) log("AttachingState executeAll due to autoAttach");
                DctController.getInstance().executeAllRequests(mId);
            }
        }

        @Override
        public void exit() {
            mResponseMsg = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    ConnectInfo connectInfo = (ConnectInfo)msg.obj;
                    RequestInfo apnRequest = connectInfo.request;
                    apnRequest.log("DcSwitchStateMachine.AttachingState: REQ_CONNECT");
                    if (DBG) log("AttachingState: REQ_CONNECT, apnRequest=" + apnRequest);

                    final PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
                    if (pb.mDcTracker.getAutoAttachOnCreation() == false) {
                        // do nothing - wait til we attach and then we'll execute all requests
                    } else {
                        apnRequest.log("DcSwitchStateMachine processing due to autoAttach");
                        DctController.getInstance().executeRequest(apnRequest);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DATA_ALLOWED: {
                    AsyncResult ar = (AsyncResult)msg.obj;
                    if (mCurrentAllowedSequence != msg.arg1) {
                        loge("EVENT_DATA_ALLOWED ignored arg1=" + msg.arg1 + ", seq=" +
                                mCurrentAllowedSequence);
                    } else {
                        boolean requestNotSupported = (ar.exception != null &&
                                ar.exception instanceof CommandException &&
                                ((CommandException)(ar.exception)).getCommandError() ==
                                        CommandException.Error.REQUEST_NOT_SUPPORTED);

                        if (ar.exception != null && !requestNotSupported) {
                            loge("EVENT_DATA_ALLOWED failed, " + ar.exception);
                            if (mResponseMsg != null) {
                                // Inform DctController about the failure.
                                Message responseMsg = Message.obtain(mResponseMsg);
                                responseMsg.obj = new AsyncResult(null, null, ar.exception);
                                responseMsg.sendToTarget();
                            }
                        } else {
                            logd("EVENT_DATA_ALLOWED success");
                            mResponseMsg = null;

                            /* If the data service state is IN_SERVICE then move to
                             * ATTACHED state.
                             */
                            int dataState = mPhone.getServiceState().getDataRegState();
                            if (dataState == ServiceState.STATE_IN_SERVICE) {
                                logd("AttachingState: Already attached, move to ATTACHED state");
                                transitionTo(mAttachedState);
                            } else {
                                logd("AttachingState: Received success on Data allowed, " +
                                     "move to Data Allowed state");
                                transitionTo(mDataAllowedState);
                            }

                        }
                    }
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_RESET: {
                    if (DBG) log("AttachingState requested abort/reset");
                    transitionTo(mIdleState);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.REQ_RETRY_CONNECT: {
                    if (DBG) log("AttachingState going to retry");
                    doEnter();
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_DATA_ATTACHED: {
                    if (DBG) {
                        log("AttachingState: EVENT_DATA_ATTACHED");
                    }
                    transitionTo(mAttachedState);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("AttachingState: REQ_DISCONNECT_ALL" );
                    }
                    final PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
                    if (pb.mDcTracker.getAutoAttachOnCreation()) {
                        // if AutoAttachOnCreation, then we may have executed requests
                        // without ever actually getting to Attached, so release the request
                        // here in that case.
                        if (DBG) log("releasingAll due to autoAttach");
                        DctController.getInstance().releaseAllRequests(mId);
                    }

                    // Wait for data allowed response to allow further
                    // changes in DDS configuration.
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;
                }

                default:
                    if (VDBG) {
                        log("AttachingState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class DataAllowedState extends State {
        private int mCurrentAllowedSequence = 0;
        @Override
        public void enter() {
            log("DataAllowedState: enter");
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("DataAllowedState: REQ_DISCONNECT_ALL" );
                    }
                    final PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
                    if (pb.mDcTracker.getAutoAttachOnCreation()) {
                        // if AutoAttachOnCreation, then we may have executed requests
                        // without ever actually getting to Attached, so release the request
                        // here in that case.
                        if (DBG) log("releasingAll due to autoAttach");
                        DctController.getInstance().releaseAllRequests(mId);
                    }
                    transitionTo(mIdleState);
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("DataAllowedState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class AttachedState extends State {
        @Override
        public void enter() {
            if (DBG) log("AttachedState: enter");
            //When enter attached state, we need exeute all requests.
            DctController.getInstance().executeAllRequests(mId);
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    ConnectInfo connectInfo = (ConnectInfo)msg.obj;
                    RequestInfo apnRequest = connectInfo.request;
                    apnRequest.log("DcSwitchStateMachine.AttachedState: REQ_CONNECT");
                    if (DBG) log("AttachedState: REQ_CONNECT, apnRequest=" + apnRequest);

<<<<<<< HEAD
                    int dataRat = mPhone.getServiceState().getRilDataRadioTechnology();
                    if (dataRat == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN &&
                             DctController.getInstance().isDdsSwitchNeeded()) {
                        SubscriptionController subController = SubscriptionController.getInstance();
                        int ddsSubId = subController.getDefaultDataSubId();
                        int ddsPhoneId = subController.getPhoneId(ddsSubId);
                        if (mId == ddsPhoneId) {
                            logd("AttachedState: Already attached on IWLAN. " +
                                    "Retry Allow Data for Dds switch");
                            transitionTo(mAttachingState);
                        } else {
                            DctController.getInstance().executeRequest(apnRequest);
                        }
                    } else {
                        DctController.getInstance().executeRequest(apnRequest);
                    }
=======
                    DctController.getInstance().executeRequest(apnRequest);
>>>>>>> 3b7ffa09d54517799d44a089791757c9325e3130
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("AttachedState: REQ_DISCONNECT_ALL" );
                    }
                    DctController.getInstance().releaseAllRequests(mId);
                    transitionTo(mDetachingState);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_DATA_DETACHED: {
                    if (DBG) {
                        log("AttachedState: EVENT_DATA_DETACHED");
                    }
                    transitionTo(mIdleState);
                    retVal = HANDLED;
                    break;
                }

                default:
                    if (VDBG) {
                        log("AttachedState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class DetachingState extends State {
        private int mCurrentDisallowedSequence = 0;

        @Override
        public void enter() {
            if (DBG) log("DetachingState: enter");
            PhoneBase pb = (PhoneBase)((PhoneProxy)mPhone).getActivePhone();
            pb.mCi.setDataAllowed(false, obtainMessage(EVENT_DATA_DISALLOWED,
                    ++mCurrentDisallowedSequence, 0));
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcSwitchAsyncChannel.REQ_CONNECT: {
                    ConnectInfo connectInfo = (ConnectInfo)msg.obj;
                    RequestInfo apnRequest = connectInfo.request;
                    apnRequest.log("DcSwitchStateMachine.DetachingState: REQ_CONNECT");
                    if (DBG) log("DetachingState: REQ_CONNECT, apnRequest=" + apnRequest);

                    // can't process this now - wait until we return to idle
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_DATA_DETACHED: {
                    if (DBG) {
                        log("DetachingState: EVENT_DATA_DETACHED");
                    }
                    transitionTo(mIdleState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DATA_DISALLOWED: {
                    AsyncResult ar = (AsyncResult)msg.obj;
                    if (mCurrentDisallowedSequence != msg.arg1) {
                        loge("EVENT_DATA_DISALLOWED ignored arg1=" + msg.arg1 + ", seq=" +
                                mCurrentDisallowedSequence);
                    } else if (ar.exception != null) {
                        // go back to attached as we still think we are.  Notifications
                        // from the ServiceStateTracker will kick us out of attached when
                        // appropriate.
                        loge("EVENT_DATA_DISALLOWED failed, " + ar.exception);
                        transitionTo(mAttachedState);
                    }
                    retVal = HANDLED;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_DISCONNECT_ALL: {
                    if (DBG) {
                        log("DetachingState: REQ_DISCONNECT_ALL, already detaching" );
                    }
                    retVal = HANDLED;
                    break;
                }

                default:
                    if (VDBG) {
                        log("DetachingState: nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }

    private class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAc != null) {
                        if (VDBG) log("Disconnecting to previous connection mAc=" + mAc);
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        if (VDBG) log("DcDefaultState: FULL_CONNECTION reply connected");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECT");
                    mAc.disconnect();
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECTED");
                    mAc = null;
                    break;
                }
                case DcSwitchAsyncChannel.REQ_IS_IDLE_STATE: {
                    boolean val = getCurrentState() == mIdleState;
                    if (VDBG) log("REQ_IS_IDLE_STATE  isIdle=" + val);
                    mAc.replyToMessage(msg, DcSwitchAsyncChannel.RSP_IS_IDLE_STATE, val ? 1 : 0);
                    break;
                }
                case DcSwitchAsyncChannel.REQ_IS_IDLE_OR_DETACHING_STATE: {
                    boolean val = (getCurrentState() == mIdleState ||
                            getCurrentState() == mDetachingState);
                    if (VDBG) log("REQ_IS_IDLE_OR_DETACHING_STATE  isIdleDetaching=" + val);
                    mAc.replyToMessage(msg,
                            DcSwitchAsyncChannel.RSP_IS_IDLE_OR_DETACHING_STATE, val ? 1 : 0);
                    break;
                }

                case DcSwitchAsyncChannel.EVENT_EMERGENCY_CALL_STARTED: {
                    mPreEmergencyState = getCurrentState();
                    transitionTo(mEmergencyState);
                    break;
                }

                default:
                    if (DBG) {
                        log("DefaultState: shouldn't happen but ignore msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    break;
            }
            return HANDLED;
        }
    }

    @Override
    protected void log(String s) {
        Rlog.d(LOG_TAG, "[" + getName() + "] " + s);
    }
}
