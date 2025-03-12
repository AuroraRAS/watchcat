package org.w8dsci.watchcat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.widget.NestedScrollView;

import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;


public class MainActivity extends AppCompatActivity {

    private EditText tcpPortInput;
    private EditText udpPortInput;
    private SwitchMaterial switchTCP;
    private SwitchMaterial switchUDP;
    private SwitchMaterial switchSSP;
    private SwitchMaterial switchBLE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tcpPortInput = findViewById(R.id.Input_tcpport);
        udpPortInput = findViewById(R.id.input_udpport);
        switchTCP = findViewById(R.id.switchTCP);
        switchUDP = findViewById(R.id.switchUDP);
        switchSSP = findViewById(R.id.switchSSP);
        switchBLE = findViewById(R.id.switchBLE);

        NestedScrollView scrollView = findViewById(R.id.scrollView);
        scrollView.requestFocus(); // Ensure NestedScrollView gets focus on startup

        String[] permissions = {
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.POST_NOTIFICATIONS
        };
        permissionsCheckAndRequest(this, permissions);
    }

    private void permissionsCheckAndRequest(@NonNull Activity context, @NonNull String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(context, permissions, 1);
                return;
            }
        }
    }

    public void startService(View view) {

        Intent serviceIntent = new Intent(this, NetworkService.class);
        if (tcpPortInput.getText().length() > 0) {
            int port;
            try {
                port = Integer.parseInt(tcpPortInput.getText().toString());
                // Start the service to listen on the given port
                serviceIntent.putExtra("TCPPORT", port);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (udpPortInput.getText().length() > 0) {
            int port;
            try {
                port = Integer.parseInt(udpPortInput.getText().toString());
                // Start the service to listen on the given port
                serviceIntent.putExtra("UDPPORT", port);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        serviceIntent.putExtra("Listener.TCP", switchTCP.isChecked());
        serviceIntent.putExtra("Listener.UDP", switchUDP.isChecked());
        serviceIntent.putExtra("Listener.SSP", switchSSP.isChecked());
        serviceIntent.putExtra("Listener.BLE", switchBLE.isChecked());
        serviceIntent.setAction("START_SERVICE");
        android.content.ComponentName serviceName = startService(serviceIntent);
        if (serviceName == null) {
            Toast.makeText(this, "Failed to start service", Toast.LENGTH_SHORT).show();
        }else {
            Toast.makeText(this, "Service starting...", Toast.LENGTH_SHORT).show();
            // Close the activity (send the app to the background)
            finish();
        }
    }
}
