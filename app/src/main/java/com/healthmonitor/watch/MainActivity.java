package com.healthmonitor.watch;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.lang.Math;
import java.util.Timer;
import java.util.TimerTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // 加速度センサ
    private SensorManager       SnrManager;
    private Sensor              ACCLsensor;
    private SensorEventListener ACCLSensorEventListener;
    private static boolean    bDrawing = false;

    // 加速度センサをサンプリングする内部タイマー
    private Timer              SamplingTimer;

    // 加速度センサーから直接受け取る計測値 x,y,z
    double   dAccelX = 0;
    double   dAccelY = 0;
    double   dAccelZ = 0;

    // 各種閾値
    // iCheckPeriod: 計測期間 iCheckPeriod * 100(ms)
    // dActiveRatio: ユーザーが活動した回数／総サンプリング回数 の割合。　dActiveRatio 以上活動していれば異常なしとする。
    private final double    dAclThreshIni = 3.0;
    private final double    dAclThreshMax = 9.0;
    private double           dAclPulseThresh = dAclThreshIni;
    private final int        iCheckPeriod = 250;
    private final double    dActiveRatio = 0.02;

    // 計測情報描画クラス群
    TextView txACCL_X, txACCL_Y, txACCL_Z;
    TextView txAclthresh, txSum, txSampleCount;
    private static int    iSensorPeakCount = 0;
    private static int    iSampleTrigCount = 0;
    private static int    iSampledPeakCount = 0;

    // 描画用ハンドラ、警告用バイブレーション等
    private Handler            mHandler = new Handler();
    private Vibrator           VibManager;
    private static boolean   bAlarmState = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 画面表示関連の初期化
        txAclthresh         = (TextView) findViewById(R.id.id_txtAclThresh);
        txSum                = (TextView) findViewById(R.id.id_txtSum);
        txSampleCount       = (TextView) findViewById(R.id.id_txtCount);
        txACCL_X            = (TextView) findViewById(R.id.id_txtACCL_X);
        txACCL_Y            = (TextView) findViewById(R.id.id_txtACCL_Y);
        txACCL_Z            = (TextView) findViewById(R.id.id_txtACCL_Z);

        SeekBar seekBarAclThr = findViewById(R.id.id_SeekBarAccelThresh );
        seekBarAclThr.setMax((int)dAclThreshMax);
        seekBarAclThr.setProgress((int)dAclThreshIni);

        txAclthresh.setText(String.format("%s%s%s", getString(R.string.AcclThresh), Integer.toString((int) dAclThreshIni), getString(R.string.AcclUnit)));

        seekBarAclThr.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged( SeekBar seekBar, int progress, boolean fromUser) {

                        dAclPulseThresh = (double)progress;
                        txAclthresh.setText(String.format("%s%s%s", getString(R.string.AcclThresh), Integer.toString(progress), getString(R.string.AcclUnit)));
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        // 利用しない
                    }
                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        //　利用しない
                    }
                });

        // 加速度センサ、バイブレータ、サンプリングタイマの初期化
        ACCLSensorEventListener = this;
        try {
            SnrManager = (SensorManager)getSystemService(SENSOR_SERVICE);
            assert SnrManager != null;
            ACCLsensor = SnrManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            SnrManager.registerListener(ACCLSensorEventListener, ACCLsensor, SensorManager.SENSOR_DELAY_UI );
        }catch(NullPointerException nulle){
            txACCL_X.setText(getString(R.string.NoSensor));
            txACCL_Y.setText(getString(R.string.NoSensor));
            txACCL_Z.setText(getString(R.string.NoSensor));
            return;
        }

        VibManager = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        SamplingTimer = new Timer();
        SamplingTimer.schedule(new timer_SampleACCL(), 100, 100);

    }


    @Override
    protected void onResume() {
        super.onResume();

        // 何もしない
    }

    @Override
    protected void onPause() {
        super.onPause();

        // 何もしない、計測を続けます。
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        // フォーカスがある時のみ描画更新をします
        if (hasFocus){
            bDrawing = true;
        }else{
            bDrawing = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {

        // 加速度センサーの更新が不安定な事があるので、ここでは活動量カウントのみ行い
        // 実際のサンプリングは 100msタイマーで行う。
        if(e.sensor.getType() == Sensor.TYPE_ACCELEROMETER ){

            dAccelX = e.values[0];
            dAccelY = e.values[1];
            dAccelZ = e.values[2];
            double dAccelPow2 = Math.pow(dAccelX, 2) + Math.pow(dAccelY, 2) + Math.pow(dAccelZ, 2);

            // 重力以外の力がかかっている場合を判断
            if ( ( dAccelPow2 > Math.pow((9.80665 + dAclPulseThresh), 2) )
               || ( dAccelPow2 < Math.pow((9.80665 - dAclPulseThresh), 2)) ){

                // 活動量カウンタを加算してゆく
                iSensorPeakCount = IncCounter(iSensorPeakCount);
            }
        }
    }


    @Override
    public void onAccuracyChanged(Sensor arg0, int arg1) {

        // 何もしない
    }


    // 100ms で更新されるユーザー活動チェックタイマー
    public class timer_SampleACCL extends TimerTask {

        @Override
        public void run() {

            // 100ms間隔で、加速度センサーの結果を取得する。100ms中に一度でも活動があればカウントする。
            if (iSensorPeakCount > 0) {

                iSensorPeakCount = 0;
                iSampledPeakCount = IncCounter(iSampledPeakCount);
            }

            // 100ms サンプリングの総回数を記録。
            iSampleTrigCount = IncCounter(iSampleTrigCount);


            // 健康状態判断時期まで到達したかどうか判定。
            if ( iSampleTrigCount >= iCheckPeriod ) {

                // ユーザーの活動量が規定の割合以下（活動量が少ない）かどうか判定。
                if ( (double) iSampledPeakCount <= (dActiveRatio * iSampleTrigCount) ) {   // 活動量が規定以下。

                    // 警告画面を表示するフラグをセット。
                    bAlarmState = true;

                    // バイブレータを鳴らして活動量の低下を警告。
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        VibrationEffect vibrationEffect = VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE);
                        VibManager.vibrate(vibrationEffect);
                    } else {
                        VibManager.vibrate(1000);
                    }
                } else {  // 活動量が規定より大きい。

                    // 警告画面を解除するフラグをセット。
                    bAlarmState = false;
                }

                mHandler.post(new Runnable() {
                    public void run() {

                        // 警告のため背景色を変更。
                        if (bAlarmState)
                            findViewById(R.id.id_bkLayout).setBackgroundResource(R.color.colorAlarm);
                        else {
                            findViewById(R.id.id_bkLayout).setBackgroundResource(R.color.colorNormal);
                        }
                    }
                });

                // 同じ計測サイクルを繰り返す為、初期化する。
                iSampledPeakCount = 0;
                iSampleTrigCount = 0;
            }

            // 加速度センサの測定結果をプレビュー表示。
            mHandler.post( new Runnable() {
                public void run() {

                    if (bDrawing) {

                        txACCL_X.setText(String.format("%s%s", getString(R.string.axisX), Double.toString(dAccelX)));
                        txACCL_Y.setText(String.format("%s%s", getString(R.string.axisY), Double.toString(dAccelY)));
                        txACCL_Z.setText(String.format("%s%s", getString(R.string.axisZ), Double.toString(dAccelZ)));

                        txSum.setText(String.format("%s", Integer.toString(iSampledPeakCount)));
                        txSampleCount.setText(String.format("%s", Integer.toString(iSampleTrigCount)));
                    }
                }
            });
        }
    }


    // カウンタを Integer.MAX_VALUE まで加算する
    private int IncCounter(int iValue){

        int iReturnVal;

        try {
            iReturnVal = Math.addExact(iValue, 1);
        }catch (ArithmeticException ae){
            iReturnVal = Integer.MAX_VALUE;
        }

        return iReturnVal;
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();

        SamplingTimer.cancel();
        SnrManager.unregisterListener(ACCLSensorEventListener);

        iSensorPeakCount = 0;
        iSampledPeakCount = 0;
        iSampleTrigCount = 0;
    }


    public void onClickCloseApp(View view){

        this.finishAndRemoveTask();
    }

}


