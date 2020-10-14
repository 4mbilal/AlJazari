package hujra.baari.aljazari;

import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Timer;
import java.util.TimerTask;

import static java.lang.Math.abs;
import hujra.baari.aljazari.bluetooth.OptionsActivity;
import hujra.baari.aljazari.joystick.JoystickMovedListener;
import hujra.baari.aljazari.bluetooth.DeviceListActivity;
import hujra.baari.aljazari.bluetooth.BluetoothRfcommClient;
import hujra.baari.aljazari.joystick.SingleJoystickView;


public class SingleJoystickControllerActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    // debug / logs
    private final boolean D = false;
    private static final String TAG = SingleJoystickControllerActivity.class.getSimpleName();

    // Message types sent from the BluetoothRfcommClient Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    // Key names received from the BluetoothRfcommClient Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    // Name of the connected device
    private String mConnectedDeviceName = null;
    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;
    // Member object for the RFCOMM services
    private BluetoothRfcommClient mRfcommClient = null;

    // Layout View
    //DualJoystickView mDualJoystick;
    SingleJoystickView mSingleJoystick;
    private Button mButtonA;
    private Button mButtonB;
    private Button mButtonC;
    private Button mButtonD;
    private TextView mTxtStatus;
    private TextView mTxtDataL;
    private TextView mTxtDataR;

    // Menu
    private MenuItem mItemConnect;
    private MenuItem mItemOptions;
    private MenuItem mItemAbout;

    // polar coordinates
    private double mRadiusL = 0, mRadiusR = 0;
    private double mAngleL = 0, mAngleR = 0;
    private boolean mCenterL = true, mCenterR = true;
    private int mDataFormat;
    private float lspeed = 0;
    private float rspeed = 0;
    private float lrdir = 0;

    // button data
    private String mStrA;
    private String mStrB;
    private String mStrC;
    private String mStrD;

    // timer task
    private Timer mUpdateTimer;
    private int mTimeoutCounter = 0;
    private int mMaxTimeoutCount; // actual timeout = count * updateperiod
    private long mUpdatePeriod;


    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_rover_controller);

        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // If BT is not on, request that it be enabled.
        if (!mBluetoothAdapter.isEnabled()){
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        }

        // Initialize the BluetoothRfcommClient to perform bluetooth connections
        mRfcommClient = new BluetoothRfcommClient(this, mHandler);

//        mDualJoystick = (DualJoystickView)findViewById(R.id.dualjoystickView);
//        mDualJoystick.setOnJostickMovedListener(_listenerLeft, _listenerRight);
        mSingleJoystick = (SingleJoystickView)findViewById(R.id.singlejoystickView);
        mSingleJoystick.setOnJostickMovedListener(_listenerLeft);

        // mDualJoystick.setYAxisInverted(false, false);

        mTxtStatus = (TextView) findViewById(R.id.txt_status);
        mTxtDataL = (TextView) findViewById(R.id.txt_dataL);
        // mTxtDataR = (TextView) findViewById(R.id.txt_dataR);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        // mUpdatePeriod = prefs.getLong( "updates_interval", 200 ); // in milliseconds
        mUpdatePeriod = Long.parseLong(prefs.getString( "updates_interval", "200" ));
        mMaxTimeoutCount = Integer.parseInt(prefs.getString( "maxtimeout_count", "20" ));
        mDataFormat = Integer.parseInt(prefs.getString( "data_format", "7" ));

        mStrA = prefs.getString( "btnA_data", "A" );
        mStrB = prefs.getString( "btnB_data", "B" );
        mStrC = prefs.getString( "btnC_data", "C" );
        mStrD = prefs.getString( "btnD_data", "D" );

        mButtonA = (Button) findViewById(R.id.button_A);
        mButtonA.setOnClickListener(arg0 -> sendMessage( mStrA ));

        mButtonB = (Button) findViewById(R.id.button_B);
        mButtonB.setOnClickListener(arg0 -> sendMessage( mStrB ));

        mButtonC = (Button) findViewById(R.id.button_C);
        mButtonC.setOnClickListener(arg0 -> sendMessage( mStrC ));

        mButtonD = (Button) findViewById(R.id.button_D);
        mButtonD.setOnClickListener(arg0 -> sendMessage( mStrD ));

        // fix me: use Runnable class instead
        mUpdateTimer = new Timer();
        mUpdateTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                UpdateMethod();
            }
        }, 2000, mUpdatePeriod);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mItemConnect = menu.add("Connect");
        mItemOptions = menu.add("Options");
        mItemAbout = menu.add("About");
        return (super.onCreateOptionsMenu(menu));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if ( item == mItemConnect ) {
            Intent serverIntent = new Intent(this, DeviceListActivity.class);
            startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
        } else if ( item == mItemOptions ) {
            startActivity( new Intent(this, OptionsActivity.class) );
        } else if ( item == mItemAbout ) {
            AlertDialog about = new AlertDialog.Builder(this).create();
            about.setCancelable(false);
            about.setMessage("Binkamaat v1.0\nhttp://sites.google.com/view/4mbilal");
            about.setButton(DialogInterface.BUTTON_POSITIVE, "OK", (dialogInterface, i) -> {
            });
            about.show();
        }
        return super.onOptionsItemSelected(item);
    }

    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        switch (key) {
            case "updates_interval":
                // reschedule task
                mUpdateTimer.cancel();
                mUpdateTimer.purge();
                mUpdatePeriod = Long.parseLong(prefs.getString("updates_interval", "200"));
                mUpdateTimer = new Timer();
                mUpdateTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        UpdateMethod();
                    }
                }, mUpdatePeriod, mUpdatePeriod);
                break;
            case "maxtimeout_count":
                mMaxTimeoutCount = Integer.parseInt(prefs.getString("maxtimeout_count", "20"));
                break;
            case "data_format":
                mDataFormat = Integer.parseInt(prefs.getString("data_format", "7"));
                break;
            case "btnA_data":
                mStrA = prefs.getString("btnA_data", "A");
                break;
            case "btnB_data":
                mStrB = prefs.getString("btnB_data", "B");
                break;
            case "btnC_data":
                mStrC = prefs.getString("btnC_data", "C");
                break;
            case "btnD_data":
                mStrD = prefs.getString("btnD_data", "D");
                break;
        }
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        if (mRfcommClient != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mRfcommClient.getState() == BluetoothRfcommClient.STATE_NONE) {
                // Start the Bluetooth  RFCOMM services
                mRfcommClient.start();
            }
        }
    }

    @Override
    public void onDestroy() {
        mUpdateTimer.cancel();
        // Stop the Bluetooth RFCOMM services
        if (mRfcommClient != null) mRfcommClient.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Bluetooth Joystick")
                .setMessage("Close this controller?")
                .setPositiveButton("Yes", (dialog, which) -> finish())
                .setNegativeButton("No", null)
                .show();
    }

    private JoystickMovedListener _listenerLeft = new JoystickMovedListener() {

        public void OnMoved(int pan, int tilt) {
            mRadiusL = Math.sqrt((pan*pan) + (tilt*tilt));
            // mAngleL = Math.atan2(pan, tilt);
            mAngleL = Math.atan2(-pan, -tilt);

            //Some very stupid problem does not allow rspeed/lspeed to take values greater than 127. Arduino somehow does
            //not receive the intended values. Debugging left to some other day! Build a solid communication protocol.
            float limit = 254;
            float speed_base = -(float)tilt*limit/70; //[-255 255]
            /*if(speed_base>1)
                speed_base = speed_base + 40;
            if(speed_base>limit)
                speed_base = limit;*/

            float steer_value = (float)pan*limit/70; //[-255 255]
            lspeed = speed_base + steer_value;
            rspeed = speed_base - steer_value;
            if(lspeed<-(limit-0.5)) lspeed = -limit;
            if(rspeed<-(limit-0.5)) rspeed = -limit;
            if(lspeed>(limit-0.5)) lspeed = limit;
            if(rspeed>(limit-0.5)) rspeed = limit;

            lrdir = 0;  //wheel direction encoding in a single byte
            if(lspeed<0)
                lrdir = lrdir + 2;
            if(rspeed<0)
                lrdir = lrdir + 1;

//            mTxtDataL.setText(String.format("( r%.2f, %.0f\u00B0 )(%d,%d)", Math.min(mRadiusL, 100), mAngleL * 180 / Math.PI,pan,tilt));
            mTxtDataL.setText(String.format("(%.2f,%.2f)", lspeed,rspeed));
            lspeed = abs(lspeed);
            rspeed = abs(rspeed);

            mCenterL = false;
        }

        public void OnReleased() {
            //
        }

        public void OnReturnedToCenter() {
            mRadiusL = mAngleL = 0;
            UpdateMethod();
            mCenterL = true;
        }
    };

    /**
     * Sends a message.
     * @param message  A string of text to send.
     */
    private void sendMessage(String message){
        // Check that we're actually connected before trying anything
        if (mRfcommClient.getState() != BluetoothRfcommClient.STATE_CONNECTED) {
            // Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        // Check that there's actually something to send
        if (message.length() > 0) {
            // Get the message bytes and tell the BluetoothRfcommClient to write
            byte[] send = message.getBytes();
            mRfcommClient.write(send);
        }
    }

    private void UpdateMethod() {

        // if either of the joysticks is not on the center, or timeout occurred
        if(!mCenterL || !mCenterR || (mTimeoutCounter>=mMaxTimeoutCount && mMaxTimeoutCount>-1) ) {
            // limit to {0..10}
            byte radiusL = (byte) ( Math.min( mRadiusL, 100.0 ) );
            byte radiusR = (byte) ( Math.min( mRadiusR, 100.0 ) );
            // scale to {0..35}
            byte angleL = (byte) ( mAngleL * 18.0 / Math.PI + 36.0 + 0.5 );
            byte angleR = (byte) ( mAngleR * 18.0 / Math.PI + 36.0 + 0.5 );
            if( angleL >= 36 )	angleL = (byte)(angleL-36);
            if( angleR >= 36 )	angleR = (byte)(angleR-36);

            if (D) {
                Log.d(TAG, String.format("%d, %d, %d, %d", radiusL, angleL, radiusR, angleR ) );
            }

            if( mDataFormat==4 ) {
                // raw 4 bytes
                sendMessage( new String(new byte[] {
                        radiusL, angleL, radiusR, angleR } ) );
            }else if( mDataFormat==5 ) {
                // start with 0x55
                sendMessage( new String(new byte[] {
                        0x55, radiusL, angleL, radiusR, angleR } ) );
            }else if( mDataFormat==6 ) {
                // use STX & ETX
                sendMessage( new String(new byte[] {
                        0x02, radiusL, angleL, radiusR, angleR, 0x03 } ) );
            }else if( mDataFormat==7 ) {
                // use "$PWM=12,23*"

                // radiusL = 0 .. 10
                // angleL = 0 .. 35
                int radius = 126 * radiusL / 70; // maximum value = 100
                int angle = angleL * 10;
                double angleRadians = Math.toRadians(angle);

                int leftPwm;
                int rightPwm;

                if (angle <= 180) {
                    rightPwm = angle > 90? -radius: radius;
                    leftPwm = (int)Math.round(Math.cos(angleRadians) * radius);
                } else {
                    leftPwm = angle < 270? -radius: radius;
                    rightPwm = (int)Math.round(Math.cos(angleRadians) * radius);
                }

//                sendMessage("$PWM=" + leftPwm + "," + rightPwm + "*");
                leftPwm = -100;
                rightPwm = 0;

                //String msg = "s" + String.valueOf(Character.toChars((int)lspeed)) + String.valueOf(Character.toChars((int)rspeed)) + String.valueOf(Character.toChars((int)lspeed_dir)) + String.valueOf(Character.toChars((int)rspeed_dir));
                // A single starting character, such as 's' above, does not work due to some synch problems with Arduino.
                //Interlacing the message with 'l', 'r', 'd' removes the problem. Maybe slower rates won't have this problem.
                String msg = "l" + String.valueOf(Character.toChars((char)lspeed)) + "r" + String.valueOf(Character.toChars((char)rspeed)) + "d" + String.valueOf(Character.toChars((char)lrdir));
                sendMessage(msg);
                //sendMessage("s" + Character.toString(left)+ lspeed_dir  + Character.toString(right)+ rspeed_dir);
//                sendMessage("s" + left + lspeed_dir  + right + rspeed_dir);
//                sendMessage(lspeed_dir  + " " + rspeed_dir + "\n\r");

            }

            mTimeoutCounter = 0;
        }
        else{
            if( mMaxTimeoutCount>-1 )
                mTimeoutCounter++;
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    // Get the device MAC address
                    String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    // Get the BLuetoothDevice object
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                    // Attempt to connect to the device
                    mRfcommClient.connect(device);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode != Activity.RESULT_OK) {
                    // User did not enable Bluetooth or an error occurred
                    Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
                    finish();
                }
                break;
        }
    }

    // The Handler that gets information back from the BluetoothRfcommClient
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothRfcommClient.STATE_CONNECTED:
                            mTxtStatus.setText(R.string.title_connected_to);
                            mTxtStatus.append(" " + mConnectedDeviceName);
                            break;
                        case BluetoothRfcommClient.STATE_CONNECTING:
                            mTxtStatus.setText(R.string.title_connecting);
                            break;
                        case BluetoothRfcommClient.STATE_NONE:
                            mTxtStatus.setText(R.string.title_not_connected);
                            break;
                    }
                    break;
                case MESSAGE_READ:
                    // byte[] readBuf = (byte[]) msg.obj;
                    // int data_length = msg.arg1;
                    break;
                case MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                    Toast.makeText(getApplicationContext(), "Connected to "
                            + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    break;
                case MESSAGE_TOAST:
                    Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                            Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };
}


