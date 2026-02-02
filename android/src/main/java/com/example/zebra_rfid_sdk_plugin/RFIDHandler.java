package com.example.zebra_rfid_sdk_plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zebra.rfid.api3.ACCESS_OPERATION_CODE;
import com.zebra.rfid.api3.Antennas;
import com.zebra.rfid.api3.ENUM_TRANSPORT;
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE;
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE;
import com.zebra.rfid.api3.INVENTORY_STATE;
import com.zebra.rfid.api3.InvalidUsageException;
import com.zebra.rfid.api3.OperationFailureException;
import com.zebra.rfid.api3.RFIDReader;
import com.zebra.rfid.api3.ReaderDevice;
import com.zebra.rfid.api3.Readers;
import com.zebra.rfid.api3.RfidEventsListener;
import com.zebra.rfid.api3.RfidReadEvents;
import com.zebra.rfid.api3.RfidStatusEvents;
import com.zebra.rfid.api3.SESSION;
import com.zebra.rfid.api3.SL_FLAG;
import com.zebra.rfid.api3.START_TRIGGER_TYPE;
import com.zebra.rfid.api3.STATUS_EVENT_TYPE;
import com.zebra.rfid.api3.STOP_TRIGGER_TYPE;
import com.zebra.rfid.api3.TagData;
import com.zebra.rfid.api3.TriggerInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;

public class RFIDHandler implements Readers.RFIDReaderEventHandler {
    private String TAG = "RFIDHandler";
    Context context;
    CompatibleContext compatibleContext; 

    public Handler mEventHandler = new Handler(Looper.getMainLooper());
    private AsyncTask<Void, Void, String> AutoConnectDeviceTask;
    private static Readers readers;
    //    private static ArrayList<ReaderDevice> availableRFIDReaderList;
    private static ReaderDevice readerDevice;
    private static RFIDReader reader;
    private static ENUM_TRANSPORT currentTransport = null;
    private int MAX_POWER = 270;
    private IEventHandler eventHandler = new IEventHandler();
    private Function<String, Map<String, Object>> _emit;
    private EventChannel.EventSink sink = null;


    private void emit(final String eventName, final HashMap map) {
        map.put("eventName", eventName);
        mEventHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sink != null) {
                    sink.success(map);
                }
            }
        });
    }

    RFIDHandler(Context _context) {
        context = _context;

        // Wrap the context to handle Android 13+ receiver registration
        compatibleContext = new CompatibleContext(_context);

    }

    public void setEventSink(EventChannel.EventSink _sink){
        sink = _sink;
    }

    @SuppressLint("StaticFieldLeak")
    public void connect(final Result result) {
        connectToReader(null, result);
    }

    @SuppressLint("StaticFieldLeak")
    public void connectToReader(final String readerName, final Result result) {
        Readers.attach(this);
        if (readers == null) {
           // Use compatibleContext instead of context
           readers = new Readers(compatibleContext, ENUM_TRANSPORT.ALL);
        }
        AutoConnectDevice(readerName, null, result);
    }

    public void dispose() {
        try {
            if (readers != null) {
                readerDevice=null;
                reader = null;
                currentTransport = null;
                readers.Dispose();
                readers = null;
                HashMap<String, Object> map =new HashMap<>();
                map.put("status", Base.ConnectionStatus.UnConnection.ordinal());
                emit(Base.RfidEngineEvents.ConnectionStatus,map);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @SuppressLint("StaticFieldLeak")
    public void AutoConnectDevice(final String readerName, final ENUM_TRANSPORT preferredTransport, final Result result) {
        AutoConnectDeviceTask = new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                Log.d(TAG, "AutoConnectDevice - readerName: " + readerName + ", preferredTransport: " + preferredTransport);
                try {
                    // If a specific reader name is provided, search for it
                    if (readerName != null && !readerName.isEmpty()) {
                        return connectToSpecificReader(readerName);
                    }

                    // If preferred transport is specified, try that first
                    if (preferredTransport != null) {
                        String error = tryConnectWithTransport(preferredTransport);
                        if (error == null) {
                            return null; // Success
                        }
                        Log.d(TAG, "Failed to connect with preferred transport: " + error);
                    }

                    // Try transports in priority order: BLUETOOTH -> SERVICE_USB -> SERVICE_SERIAL
                    ENUM_TRANSPORT[] transports = {
                        ENUM_TRANSPORT.BLUETOOTH,
                        ENUM_TRANSPORT.SERVICE_USB,
                        ENUM_TRANSPORT.SERVICE_SERIAL
                    };

                    for (ENUM_TRANSPORT transport : transports) {
                        String error = tryConnectWithTransport(transport);
                        if (error == null) {
                            return null; // Success
                        }
                        Log.d(TAG, "Failed to connect with transport " + transport + ": " + error);
                    }

                    // Fallback to ALL transport (original behavior)
                    if (readerDevice == null) {
                        ArrayList<ReaderDevice> readersListArray = readers.GetAvailableRFIDReaderList();
                        if (readersListArray.size() > 0) {
                            readerDevice = readersListArray.get(0);
                            reader = readerDevice.getRFIDReader();
                            currentTransport = ENUM_TRANSPORT.ALL;
                        } else {
                            return "No connectable device detected";
                        }
                    }

                    if (reader != null && !reader.isConnected() && !this.isCancelled()) {
                        reader.connect();
                        ConfigureReader();
                    }

                } catch (InvalidUsageException ex) {
                    Log.d(TAG, "InvalidUsageException");
                    return ex.getMessage();
                } catch (OperationFailureException e) {
                    String details = e.getStatusDescription();
                    return details;
                }
                return null;
            }

            @Override
            protected void onPostExecute(String error) {
                Base.ConnectionStatus status=Base.ConnectionStatus.ConnectionRealy;
                super.onPostExecute(error);
                if (error != null) {
                    emit(Base.RfidEngineEvents.Error, transitionEntity(Base.ErrorResult.error(error)));
                    status=Base.ConnectionStatus.ConnectionError;
                }
                HashMap<String, Object> map =new HashMap<>();
                map.put("status",status.ordinal());
                emit(Base.RfidEngineEvents.ConnectionStatus,map);
                if (result != null) {
                    if (error != null) {
                        result.error("CONNECTION_FAILED", error, null);
                    } else {
                        result.success("Connected");
                    }
                }
            }

            @Override
            protected void onCancelled() {
                super.onCancelled();
                AutoConnectDeviceTask = null;
            }

        }.execute();
    }

    private String tryConnectWithTransport(ENUM_TRANSPORT transport) {
        try {
            // Dispose existing readers if transport changed
            if (readers != null && currentTransport != null && currentTransport != transport) {
                try {
                    readers.Dispose();
                } catch (Exception e) {
                    // Ignore dispose errors
                }
                readers = null;
            }

            // Create new Readers instance with specific transport
            if (readers == null) {
                readers = new Readers(compatibleContext, transport);
                Readers.attach(this);
            }

            ArrayList<ReaderDevice> readersListArray = readers.GetAvailableRFIDReaderList();
            if (readersListArray.size() > 0) {
                readerDevice = readersListArray.get(0);
                reader = readerDevice.getRFIDReader();
                currentTransport = transport;

                if (reader != null && !reader.isConnected()) {
                    reader.connect();
                    ConfigureReader();
                    return null; // Success
                }
                return "Reader found but connection failed";
            }
            return "No readers found on " + transport;
        } catch (InvalidUsageException | OperationFailureException e) {
            return e.getMessage();
        }
    }

    private String connectToSpecificReader(String readerName) {
        try {
            // Try all transports to find the reader
            ENUM_TRANSPORT[] transports = {
                ENUM_TRANSPORT.BLUETOOTH,
                ENUM_TRANSPORT.SERVICE_USB,
                ENUM_TRANSPORT.SERVICE_SERIAL
            };

            for (ENUM_TRANSPORT transport : transports) {
                try {
                    // Dispose existing readers if transport changed
                    if (readers != null && currentTransport != null && currentTransport != transport) {
                        try {
                            readers.Dispose();
                        } catch (Exception e) {
                            // Ignore dispose errors
                        }
                        readers = null;
                    }

                    // Create new Readers instance with specific transport
                    if (readers == null) {
                        readers = new Readers(compatibleContext, transport);
                        Readers.attach(this);
                    }

                    ArrayList<ReaderDevice> readersListArray = readers.GetAvailableRFIDReaderList();
                    for (ReaderDevice device : readersListArray) {
                        if (device.getName() != null && device.getName().contains(readerName)) {
                            readerDevice = device;
                            reader = readerDevice.getRFIDReader();
                            currentTransport = transport;

                            if (reader != null && !reader.isConnected()) {
                                reader.connect();
                                ConfigureReader();
                                return null; // Success
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Error trying transport " + transport + ": " + e.getMessage());
                }
            }

            return "Reader '" + readerName + "' not found";
        } catch (Exception e) {
            return "Error searching for reader: " + e.getMessage();
        }
    }

    private boolean isReaderConnected() {
        if (reader != null && reader.isConnected())
            return true;
        else {
            Log.d(TAG, "reader is not connected");
            return false;
        }
    }

    private synchronized void ConfigureReader() {
        Log.d(TAG, "ConfigureReader " + reader.getHostName());
        if (reader.isConnected()) {
            TriggerInfo triggerInfo = new TriggerInfo();
            triggerInfo.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
            triggerInfo.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);
            try {
                // receive events from reader
                reader.Events.addEventsListener(eventHandler);
                // HH event
                reader.Events.setHandheldEvent(true);
                // tag event with tag data
                reader.Events.setTagReadEvent(true);
                reader.Events.setAttachTagDataWithReadEvent(false);
                // set trigger mode as rfid so scanner beam will not come
                reader.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true);
                // set start and stop triggers
                reader.Config.setStartTrigger(triggerInfo.StartTrigger);
                reader.Config.setStopTrigger(triggerInfo.StopTrigger);
                // power levels are index based so maximum power supported get the last one
                MAX_POWER = reader.ReaderCapabilities.getTransmitPowerLevelValues().length - 1;
                // set antenna configurations
                Antennas.AntennaRfConfig config = reader.Config.Antennas.getAntennaRfConfig(1);
                config.setTransmitPowerIndex(MAX_POWER);
                config.setrfModeTableIndex(0);
                config.setTari(0);
                reader.Config.Antennas.setAntennaRfConfig(1, config);
                // Set the singulation control
                Antennas.SingulationControl s1_singulationControl = reader.Config.Antennas.getSingulationControl(1);
                s1_singulationControl.setSession(SESSION.SESSION_S0);
                s1_singulationControl.Action.setInventoryState(INVENTORY_STATE.INVENTORY_STATE_A);
                s1_singulationControl.Action.setSLFlag(SL_FLAG.SL_ALL);
                reader.Config.Antennas.setSingulationControl(1, s1_singulationControl);
                // delete any prefilters
                reader.Actions.PreFilters.deleteAll();
                //
            } catch (InvalidUsageException | OperationFailureException e) {
                e.printStackTrace();
            }
        }
    }

    ///Get reader information
    public   ArrayList<ReaderDevice> getReadersList() {
        ArrayList<ReaderDevice> readersListArray=new  ArrayList<ReaderDevice>();
        try {
            if(readers!=null) {
                readersListArray = readers.GetAvailableRFIDReaderList();
                return readersListArray;
            }
        }catch (InvalidUsageException e){
//            emit(Base.RfidEngineEvents.Error, transitionEntity(Base.ErrorResult.error(error)));
        }
        return  readersListArray;
    }

    ///Get available readers with metadata
    public void getAvailableReaders(final Result result) {
        new AsyncTask<Void, Void, ArrayList<HashMap<String, Object>>>() {
            @Override
            protected ArrayList<HashMap<String, Object>> doInBackground(Void... voids) {
                ArrayList<HashMap<String, Object>> readersList = new ArrayList<>();
                try {
                    // Try all transports to get complete list
                    ENUM_TRANSPORT[] transports = {
                        ENUM_TRANSPORT.BLUETOOTH,
                        ENUM_TRANSPORT.SERVICE_USB,
                        ENUM_TRANSPORT.SERVICE_SERIAL
                    };

                    for (ENUM_TRANSPORT transport : transports) {
                        try {
                            Readers tempReaders = new Readers(compatibleContext, transport);
                            ArrayList<ReaderDevice> devices = tempReaders.GetAvailableRFIDReaderList();
                            
                            for (ReaderDevice device : devices) {
                                HashMap<String, Object> readerInfo = new HashMap<>();
                                readerInfo.put("name", device.getName() != null ? device.getName() : "Unknown");
                                readerInfo.put("address", device.getAddress() != null ? device.getAddress() : "N/A");
                                readerInfo.put("transport", transport.toString());
                                
                                // Check if this reader is already in the list (avoid duplicates)
                                boolean exists = false;
                                for (HashMap<String, Object> existing : readersList) {
                                    if (existing.get("name").equals(readerInfo.get("name")) && 
                                        existing.get("address").equals(readerInfo.get("address"))) {
                                        exists = true;
                                        break;
                                    }
                                }
                                
                                if (!exists) {
                                    readersList.add(readerInfo);
                                }
                            }
                            
                            tempReaders.Dispose();
                        } catch (Exception e) {
                            Log.d(TAG, "Error getting readers for transport " + transport + ": " + e.getMessage());
                        }
                    }

                    // Also check with ALL transport as fallback
                    try {
                        Readers allReaders = new Readers(compatibleContext, ENUM_TRANSPORT.ALL);
                        ArrayList<ReaderDevice> devices = allReaders.GetAvailableRFIDReaderList();
                        
                        for (ReaderDevice device : devices) {
                            HashMap<String, Object> readerInfo = new HashMap<>();
                            readerInfo.put("name", device.getName() != null ? device.getName() : "Unknown");
                            readerInfo.put("address", device.getAddress() != null ? device.getAddress() : "N/A");
                            readerInfo.put("transport", "ALL");
                            
                            // Check if this reader is already in the list
                            boolean exists = false;
                            for (HashMap<String, Object> existing : readersList) {
                                if (existing.get("name").equals(readerInfo.get("name")) && 
                                    existing.get("address").equals(readerInfo.get("address"))) {
                                    exists = true;
                                    break;
                                }
                            }
                            
                            if (!exists) {
                                readersList.add(readerInfo);
                            }
                        }
                        
                        allReaders.Dispose();
                    } catch (Exception e) {
                        Log.d(TAG, "Error getting readers with ALL transport: " + e.getMessage());
                    }

                } catch (Exception e) {
                    Log.d(TAG, "Error in getAvailableReaders: " + e.getMessage());
                }
                return readersList;
            }

            @Override
            protected void onPostExecute(ArrayList<HashMap<String, Object>> readersList) {
                if (result != null) {
                    result.success(readersList);
                }
            }
        }.execute();
    }

    ///Get current connection type
    public void getConnectionType(final Result result) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                if (currentTransport != null) {
                    return currentTransport.toString();
                }
                return null;
            }

            @Override
            protected void onPostExecute(String transportType) {
                if (result != null) {
                    result.success(transportType);
                }
            }
        }.execute();
    }
    
    public void locateTag(final String tagID, final Result result) {
        if (!isReaderConnected()) {
            result.error("UNAVAILABLE", "Reader not connected", null);
            return;
        }
    
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                try {
                    reader.Actions.TagLocationing.Perform(tagID, null, null);
                    Thread.sleep(5000);  // Wait for 5 seconds to locate the tag
                    return "Tag location started";
                } catch (InvalidUsageException | OperationFailureException | InterruptedException e) {
                    return "Tag location failed: " + e.getMessage();
                }
            }
    
            @Override
            protected void onPostExecute(String resultMessage) {
                if (resultMessage.startsWith("Tag location failed")) {
                    result.error("UNAVAILABLE", resultMessage, null);
                } else {
                    result.success(resultMessage);
                }
            }
        }.execute();
    }

    public class IEventHandler implements RfidEventsListener {
        @Override
        public void eventReadNotify(RfidReadEvents rfidReadEvents) {
            // Recommended to use new method getReadTagsEx for better performance in case of large tag population
            TagData[] myTags = reader.Actions.getReadTags(100);


            if (myTags != null) {
                ArrayList<HashMap<String, Object>> datas= new ArrayList<>();
                for (int index = 0; index < myTags.length; index++) {
                    TagData tagData=myTags[index];
                    Log.d(TAG, "Tag ID " +tagData.getTagID());
                    Log.d(TAG, "Tag getOpCode " +tagData.getOpCode());
                    Log.d(TAG, "Tag getOpStatus " +tagData.getOpStatus());

                    ///read operation
                    if(tagData.getOpCode()==null || tagData.getOpCode()== ACCESS_OPERATION_CODE.ACCESS_OPERATION_READ){
                        //&&tagData.getOpStatus()== ACCESS_OPERATION_STATUS.ACCESS_SUCCESS
                        Base.RfidData data=new Base.RfidData();
                        data.tagID=tagData.getTagID();
                        data.antennaID=tagData.getAntennaID();
                        data.peakRSSI=tagData.getPeakRSSI();
                        data.opStatus=tagData.getOpStatus();
                        data.allocatedSize=tagData.getTagIDAllocatedSize();
                        data.lockData=tagData.getPermaLockData();
                        if(tagData.isContainsLocationInfo()){
                            data.relativeDistance=tagData.LocationInfo.getRelativeDistance();
                        }
                        data.memoryBankData=tagData.getMemoryBankData();
                        datas.add(transitionEntity(data) );
                    }
                }

                if(datas.size()>0){
                    new AsyncDataNotify().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, datas);
                }
            }
        }

        @Override
        public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
            Log.d(TAG, "Status Notification: " + rfidStatusEvents.StatusEventData.getStatusEventType());
            if (rfidStatusEvents.StatusEventData.getStatusEventType() == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED)
                    new AsyncTask<Void, Void, Void>() {
                        @Override
                        protected Void doInBackground(Void... voids) {
                            handleTriggerPress(true);
                            return null;
                        }
                    }.execute();
            }
            if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... voids) {
                        handleTriggerPress(false);
                        return null;
                    }
                }.execute();
            }
        }


    }




    public void handleTriggerPress(boolean pressed) {
        if (pressed) {
            performInventory();
        } else
            stopInventory();
    }


    synchronized void performInventory() {
        // check reader connection
        if (!isReaderConnected())
            return;
        try {
            reader.Actions.Inventory.perform();
        } catch (InvalidUsageException e) {
            e.printStackTrace();
        } catch (OperationFailureException e) {
            e.printStackTrace();
        }
    }

    synchronized void stopInventory() {
        // check reader connection
        if (!isReaderConnected())
            return;
        try {
            reader.Actions.Inventory.stop();
        } catch (InvalidUsageException e) {
            e.printStackTrace();
        } catch (OperationFailureException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void RFIDReaderAppeared(ReaderDevice readerDevice) {
        Log.d(TAG, "RFIDReaderAppeared " + readerDevice.getName());
//        new ConnectionTask().execute();
    }

    @Override
    public void RFIDReaderDisappeared(ReaderDevice readerDevice) {
        Log.d(TAG, "RFIDReaderDisappeared " + readerDevice.getName());
//        if (readerDevice.getName().equals(reader.getHostName()))
//            disconnect();
        dispose();
    }

    private  class AsyncDataNotify extends AsyncTask<ArrayList<HashMap<String, Object>>, Void, Void> {
        @Override
        protected Void doInBackground(ArrayList<HashMap<String, Object>>... params) {
            HashMap<String,Object> hashMap=new HashMap<>();
            hashMap.put("datas",params[0]);
            emit(Base.RfidEngineEvents.ReadRfid,hashMap);
            return null;
        }
    }


    //Entity class transfer HashMap
    public static HashMap<String, Object> transitionEntity(Object onClass) {
        HashMap<String, Object> hashMap = new HashMap<String, Object>();
        Field[] fields = onClass.getClass().getDeclaredFields();
        for (Field field : fields) {
            //Make private variables accessible during reflection
            field.setAccessible(true);
            try {
                hashMap.put(field.getName(), field.get(onClass));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return hashMap;
    }
}