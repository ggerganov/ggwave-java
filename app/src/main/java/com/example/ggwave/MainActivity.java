package com.example.ggwave;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.nio.ShortBuffer;

public class MainActivity extends AppCompatActivity {

    private String kMessageToSend = "Hello Android!";
    private CapturingThread mCapturingThread;
    private PlaybackThread mPlaybackThread;
    private static final int REQUEST_RECORD_AUDIO = 13;

    // Native interface:
    private native void initNative();
    private native void processCaptureData(short[] data);
    private native void sendMessage(String message);

    // Native callbacks:
    private void onNativeReceivedMessage(byte c_message[]) {
        String message = new String(c_message);
        Log.v("ggwave", "Received message: " + message);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView textView = (TextView) findViewById(R.id.textViewReceived);
                textView.setText("Received: " + message);
            }
        });
    }

    private void onNativeMessageEncoded(short c_message[]) {
        Log.v("ggwave", "Playing encoded message ..");

        mPlaybackThread = new PlaybackThread(c_message, new PlaybackListener() {
            @Override
            public void onProgress(int progress) {
                // todo : progress updates
            }
            @Override
            public void onCompletion() {
                mPlaybackThread.stopPlayback();
                ((Button) findViewById(R.id.buttonTogglePlayback)).setText("Send Message");
                ((TextView) findViewById(R.id.textViewStatusOut)).setText("Status: Idle");
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        System.loadLibrary("test-cpp");
        initNative();

        mCapturingThread = new CapturingThread(new AudioDataReceivedListener() {
            @Override
            public void onAudioDataReceived(short[] data) {
                //Log.v("ggwave", "java: 0 = " + data[0]);
                processCaptureData(data);
            }
        });

        Button buttonToggleCapture = (Button) findViewById(R.id.buttonToggleCapture);
        buttonToggleCapture.setOnClickListener( new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mCapturingThread.capturing()) {
                    startAudioCapturingSafe();
                } else {
                    mCapturingThread.stopCapturing();
                }

                if (mCapturingThread.capturing()) {
                    ((Button) findViewById(R.id.buttonToggleCapture)).setText("Stop Capturing");
                    ((TextView) findViewById(R.id.textViewStatusInp)).setText("Status: Capturing");
                    ((TextView) findViewById(R.id.textViewReceived)).setText("Received:");
                } else {
                    ((Button) findViewById(R.id.buttonToggleCapture)).setText("Start Capturing");
                    ((TextView) findViewById(R.id.textViewStatusInp)).setText("Status: Idle");
                    ((TextView) findViewById(R.id.textViewReceived)).setText("Received:");
                }
            }
        });

        ((TextView) findViewById(R.id.textViewMessageToSend)).setText("Message to send: " + kMessageToSend);

        Button buttonTogglePlayback = (Button) findViewById(R.id.buttonTogglePlayback);
        buttonTogglePlayback.setOnClickListener( new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mPlaybackThread == null || !mPlaybackThread.playing()) {
                    sendMessage(kMessageToSend);
                    mPlaybackThread.startPlayback();
                } else {
                    mPlaybackThread.stopPlayback();
                }

                if (mPlaybackThread.playing()) {
                    ((Button) findViewById(R.id.buttonTogglePlayback)).setText("Stop Playing");
                    ((TextView) findViewById(R.id.textViewStatusOut)).setText("Status: Playing audio");
                } else {
                    ((Button) findViewById(R.id.buttonTogglePlayback)).setText("Send Message");
                    ((TextView) findViewById(R.id.textViewStatusOut)).setText("Status: Idle");
                }
            }
        });
    }

    private void startAudioCapturingSafe() {
        Log.i("ggwave", "startAudioCapturingSafe");

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Log.i("ggwave", " - record permission granted");
            mCapturingThread.startCapturing();
        } else {
            Log.i("ggwave", " - record permission NOT granted");
            requestMicrophonePermission();
        }
    }

    private void requestMicrophonePermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.RECORD_AUDIO)) {
            new AlertDialog.Builder(this)
                    .setTitle("Microphone Access Requested")
                    .setMessage("Microphone access is required in order to receive audio messages")
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                                    android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
                        }
                    })
                    .setNegativeButton(android.R.string.no, null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
        } else {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                    android.Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            mCapturingThread.stopCapturing();
        }
    }
}

interface AudioDataReceivedListener {
    void onAudioDataReceived(short[] data);
}

class CapturingThread {
    private static final String LOG_TAG = CapturingThread.class.getSimpleName();
    private static final int SAMPLE_RATE = 48000;

    public CapturingThread(AudioDataReceivedListener listener) {
        mListener = listener;
    }

    private boolean mShouldContinue;
    private AudioDataReceivedListener mListener;
    private Thread mThread;

    public boolean capturing() {
        return mThread != null;
    }

    public void startCapturing() {
        if (mThread != null)
            return;

        mShouldContinue = true;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                capture();
            }
        });
        mThread.start();
    }

    public void stopCapturing() {
        if (mThread == null)
            return;

        mShouldContinue = false;
        mThread = null;
    }

    private void capture() {
        Log.v(LOG_TAG, "Start");
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        // buffer size in bytes
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2;
        }
        bufferSize = 4*1024;

        short[] audioBuffer = new short[bufferSize / 2];

        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        Log.d("ggwave", "buffer size = " + bufferSize);
        Log.d("ggwave", "Sample rate = " + record.getSampleRate());

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return;
        }
        record.startRecording();

        Log.v(LOG_TAG, "Start capturing");

        long shortsRead = 0;
        while (mShouldContinue) {
            int numberOfShort = record.read(audioBuffer, 0, audioBuffer.length);
            shortsRead += numberOfShort;

            mListener.onAudioDataReceived(audioBuffer);
        }

        record.stop();
        record.release();

        Log.v(LOG_TAG, String.format("Capturing stopped. Samples read: %d", shortsRead));
    }
}

interface PlaybackListener {
    void onProgress(int progress);
    void onCompletion();
}

class PlaybackThread {
    static final int SAMPLE_RATE = 48000;
    private static final String LOG_TAG = PlaybackThread.class.getSimpleName();

    public PlaybackThread(short[] samples, PlaybackListener listener) {
        mSamples = ShortBuffer.wrap(samples);
        mNumSamples = samples.length;
        mListener = listener;
    }

    private Thread mThread;
    private boolean mShouldContinue;
    private ShortBuffer mSamples;
    private int mNumSamples;
    private PlaybackListener mListener;

    public boolean playing() {
        return mThread != null;
    }

    public void startPlayback() {
        if (mThread != null)
            return;

        // Start streaming in a thread
        mShouldContinue = true;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                play();
            }
        });
        mThread.start();
    }

    public void stopPlayback() {
        if (mThread == null)
            return;

        mShouldContinue = false;
        mThread = null;
    }

    private void play() {
        int bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize == AudioTrack.ERROR || bufferSize == AudioTrack.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2;
        }

        bufferSize = 16*1024;

        AudioTrack audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM);

        audioTrack.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onPeriodicNotification(AudioTrack track) {
                if (mListener != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    mListener.onProgress((track.getPlaybackHeadPosition() * 1000) / SAMPLE_RATE);
                }
            }
            @Override
            public void onMarkerReached(AudioTrack track) {
                Log.v(LOG_TAG, "Audio file end reached");
                track.release();
                if (mListener != null) {
                    mListener.onCompletion();
                }
            }
        });
        audioTrack.setPositionNotificationPeriod(SAMPLE_RATE / 30); // 30 times per second
        audioTrack.setNotificationMarkerPosition(mNumSamples);

        audioTrack.play();

        Log.v(LOG_TAG, "Audio streaming started");

        short[] buffer = new short[bufferSize];
        mSamples.rewind();
        int limit = mNumSamples;
        int totalWritten = 0;
        while (mSamples.position() < limit && mShouldContinue) {
            int numSamplesLeft = limit - mSamples.position();
            int samplesToWrite;
            if (numSamplesLeft >= buffer.length) {
                mSamples.get(buffer);
                samplesToWrite = buffer.length;
            } else {
                for(int i = numSamplesLeft; i < buffer.length; i++) {
                    buffer[i] = 0;
                }
                mSamples.get(buffer, 0, numSamplesLeft);
                samplesToWrite = numSamplesLeft;
            }
            totalWritten += samplesToWrite;
            audioTrack.write(buffer, 0, samplesToWrite);
        }

        if (!mShouldContinue) {
            audioTrack.release();
        }

        Log.v(LOG_TAG, "Audio streaming finished. Samples written: " + totalWritten);
    }
}
