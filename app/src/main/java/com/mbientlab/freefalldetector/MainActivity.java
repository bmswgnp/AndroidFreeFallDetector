/*
 * Copyright 2015 MbientLab Inc. All rights reserved.
 */

package com.mbientlab.freefalldetector;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.mbientlab.metawear.AsyncOperation;
import com.mbientlab.metawear.Message;
import com.mbientlab.metawear.MetaWearBleService;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.RouteManager;
import com.mbientlab.metawear.UnsupportedModuleException;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.Debug;
import com.mbientlab.metawear.module.Logging;
import com.mbientlab.metawear.processor.Average;
import com.mbientlab.metawear.processor.Comparison;
import com.mbientlab.metawear.processor.Rss;
import com.mbientlab.metawear.processor.Threshold;

public class MainActivity extends Activity implements ServiceConnection {

    private static final String LOG_TAG = "FreeFallDetector", FREE_FALL_KEY= "free_fall_key", NO_FREE_FALL_KEY= "no_free_fall_key";
    private MetaWearBleService.LocalBinder serviceBinder;
    private MetaWearBoard mwBoard;
    private Accelerometer accelModule;
    private Debug debugModule;
    private Logging loggingModule;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getApplicationContext().bindService(new Intent(this, MetaWearBleService.class), this, Context.BIND_AUTO_CREATE);

        findViewById(R.id.start_accel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loggingModule.startLogging(true);
                accelModule.enableAxisSampling();
                accelModule.start();
            }
        });
        findViewById(R.id.stop_accel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                accelModule.stop();
                accelModule.disableAxisSampling();
                loggingModule.stopLogging();

                loggingModule.downloadLog(0.f, new Logging.DownloadHandler() {
                    @Override
                    public void onProgressUpdate(int nEntriesLeft, int totalEntries) {
                        if (nEntriesLeft == 0) {
                            Log.i(LOG_TAG, "Log download complete");
                        }
                    }
                });
            }
        });
        findViewById(R.id.reset_board).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                debugModule.resetDevice();
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        getApplicationContext().unbindService(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        serviceBinder= (MetaWearBleService.LocalBinder) service;

        String mwMacAddress= "D5:7B:B9:7D:CE:0E";   ///< Put your board's MAC address here
        BluetoothManager btManager= (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothDevice btDevice= btManager.getAdapter().getRemoteDevice(mwMacAddress);

        mwBoard= serviceBinder.getMetaWearBoard(btDevice);
        mwBoard.setConnectionStateHandler(new MetaWearBoard.ConnectionStateHandler() {
            @Override
            public void connected() {
                Log.i(LOG_TAG, "Connected");

                try {
                    accelModule= mwBoard.getModule(Accelerometer.class);
                    accelModule.setOutputDataRate(50f);   ///< Set operating freq to 50Hz
                    accelModule.routeData().fromAxes()
                            .process(new Rss())
                            .process(new Average((byte) 4))
                            .process(new Threshold(0.5f, Threshold.OutputMode.BINARY))
                            .split()
                                .branch().process(new Comparison(Comparison.Operation.EQ, -1)).log(FREE_FALL_KEY)
                                .branch().process(new Comparison(Comparison.Operation.EQ, 1)).log(NO_FREE_FALL_KEY)
                            .end()
                    .commit().onComplete(new AsyncOperation.CompletionHandler<RouteManager>() {
                        @Override
                        public void success(RouteManager result) {
                            result.setLogMessageHandler(FREE_FALL_KEY, new RouteManager.MessageHandler() {
                                @Override
                                public void process(Message message) {
                                    Log.i(LOG_TAG, String.format("%tY%<tm%<td-%<tH%<tM%<tS%<tL: Entered Free Fall", message.getTimestamp()));
                                }
                            });
                            result.setLogMessageHandler(NO_FREE_FALL_KEY, new RouteManager.MessageHandler() {
                                @Override
                                public void process(Message message) {
                                    Log.i(LOG_TAG, String.format("%tY%<tm%<td-%<tH%<tM%<tS%<tL: Left Free Fall", message.getTimestamp()));
                                }
                            });
                        }
                    });

                    debugModule= mwBoard.getModule(Debug.class);
                    loggingModule= mwBoard.getModule(Logging.class);
                } catch (UnsupportedModuleException e) {
                    Log.e(LOG_TAG, "Cannot find module", e);
                }
            }

            @Override
            public void disconnected() {
                Log.i(LOG_TAG, "Disconnected");
            }
        });
        mwBoard.connect();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }
}
