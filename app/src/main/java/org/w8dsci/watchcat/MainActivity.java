package org.w8dsci.watchcat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
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

        permissionsCheckAndRequest(this);
    }

    private void permissionsCheckAndRequest(@NonNull Activity context) {
        java.util.ArrayList<String> requiredPermissions = new java.util.ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (requiredPermissions.isEmpty()) {
            return;
        }

        java.util.ArrayList<String> missingPermissions = new java.util.ArrayList<>();
        for (String permission : requiredPermissions) {
            if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(context, missingPermissions.toArray(new String[0]), 1);
        }
    }

    public void startService(View view) {

        Intent serviceIntent = new Intent(this, NetworkService.class);
        if (!applyPortExtra(tcpPortInput, "TCPPORT", serviceIntent)) return;
        if (!applyPortExtra(udpPortInput, "UDPPORT", serviceIntent)) return;

        serviceIntent.putExtra("Listener.TCP", switchTCP.isChecked());
        serviceIntent.putExtra("Listener.UDP", switchUDP.isChecked());
        serviceIntent.putExtra("Listener.SSP", switchSSP.isChecked());
        serviceIntent.putExtra("Listener.BLE", switchBLE.isChecked());
        serviceIntent.setAction("START_SERVICE");
        android.content.ComponentName serviceName = startService(serviceIntent);
        if (serviceName == null) {
            Toast.makeText(this, "Failed to start service", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Service starting...", Toast.LENGTH_SHORT).show();
            // Close the activity (send the app to the background)
            finish();
        }
    }

    private boolean applyPortExtra(@NonNull EditText input, @NonNull String extraKey, @NonNull Intent intent) {
        String portText = input.getText().toString().trim();
        if (portText.isEmpty()) {
            return true;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (port < 1 || port > 65535) {
            Toast.makeText(this, "Port must be between 1 and 65535", Toast.LENGTH_SHORT).show();
            return false;
        }

        intent.putExtra(extraKey, port);
        return true;
    }
}
