package org.w8dsci.watchcat;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCharacteristic;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.UUID;

public class NetworkService extends Service {
    private static NetworkService theService = null;
    private static final String CHANNEL_ID = "Watchcat_Channel";
    private static final String SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"; // Standard SPP UUID
    private static final UUID UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_TX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_RX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

    // Network listeners
    private TCPListener tcpListener;
    private UDPListener udpListener;
    private BluetoothListener bluetoothListener;
    private BluetoothLeListener bluetoothLeListener;

    @Override
    public void onCreate() {
        super.onCreate();
        theService = this;
    }

    public NetworkService getService() {
        return theService;
    }

    public enum Listener {TCP, UDP, SSP, BLE}

    public void startListener(Listener service, int port) {
        switch (service) {
            case TCP:
                startTCPListener(port);
                break;
            case UDP:
                startUDPListener(port);
                break;
            case SSP:
                startBluetoothListener();
                break;
            case BLE:
                startBluetoothLeListener();
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + service);
        }
    }

    public void stopListener(Listener service) {
        switch (service) {
            case TCP:
                if (tcpListener != null) tcpListener.stopListening();
                tcpListener = null;
                break;
            case UDP:
                if (udpListener != null) udpListener.stopListening();
                udpListener = null;
                break;
            case SSP:
                if (bluetoothListener != null) bluetoothListener.stopListening();
                bluetoothListener = null;
                break;
            case BLE:
                if (bluetoothLeListener != null) bluetoothLeListener.stopListening();
                bluetoothLeListener = null;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + service);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ("START_SERVICE".equals(intent.getAction())) {
            int tcpPort = intent.getIntExtra("TCPPORT", 12719);
            int udpPort = intent.getIntExtra("UDPPORT", 12719);
            createNotificationChannel();

            // Initialize listeners
            if(intent.getBooleanExtra("Listener.TCP", false))
                startListener(Listener.TCP, tcpPort);
            if(intent.getBooleanExtra("Listener.UDP", false))
                startListener(Listener.UDP, udpPort);
            if(intent.getBooleanExtra("Listener.SSP", false))
                startListener(Listener.SSP, 0);
            if(intent.getBooleanExtra("Listener.BLE", false))
                startListener(Listener.BLE, 0);

            String contentText = "";
            if (tcpListener != null) contentText = "TCP,";
            if (udpListener != null) contentText += "UDP,";
            if (bluetoothListener != null) contentText += "SSP,";
            if (bluetoothLeListener != null) contentText += "BLE";
            if (tcpListener != null || udpListener != null) contentText += "\n";
            if (tcpListener != null) contentText += "T:"+tcpListener.getListenerPort();
            if (udpListener != null) contentText += ",U:"+udpListener.getListenerPort();
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("LISTENING on: ")
                    .setContentText(contentText)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .addAction(R.mipmap.ic_launcher, "Stop Service", stopServicePendingIntent())
                    .build();

            startForeground(1, notification);
        } else if ("STOP_SERVICE".equals(intent.getAction())) {
            stopListener(Listener.TCP);
            stopListener(Listener.UDP);
            stopListener(Listener.SSP);
            stopListener(Listener.BLE);
            stopForeground(STOP_FOREGROUND_REMOVE);
            theService = null;
            stopSelf();
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Watchcat Service Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        serviceChannel.setDescription("Notifications from Watchcat Service");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(serviceChannel);
    }

    private void startTCPListener(int port) {
        tcpListener = new TCPListener(port);
        new Thread(tcpListener).start();
    }

    private void startUDPListener(int port) {
        udpListener = new UDPListener(port);
        new Thread(udpListener).start();
    }

    private void startBluetoothListener() {
        try {
            bluetoothListener = new BluetoothListener();
            new Thread(bluetoothListener).start();
        } catch (IOException ignored) {

        }
    }

    private void startBluetoothLeListener() {
        bluetoothLeListener = new BluetoothLeListener();
        new Thread(bluetoothLeListener).start();
    }

    private class TCPListener extends NetworkListener {
        private ServerSocket serverSocket;

        public TCPListener(int port) {
            super();
            try {
                serverSocket = new ServerSocket(port);
                serverSocket.getLocalPort();  // Set listen port after server socket is initialized
            } catch (IOException e) {
                Log.d(this.getClass().getName(), e.toString());
            }
        }

        public int getListenerPort() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void stopListening() {
            super.stopListening();
            try {serverSocket.close();} catch (IOException ignored) {}
        }

        @Override
        public void run() {
            try {
                while (isRunning) {
                    Socket clientSocket = serverSocket.accept();
                    handleTCPConnection(clientSocket);
                }
            } catch (IOException e) {
                Log.d(this.getClass().getName(), e.toString());
            }
        }

        private void handleTCPConnection(Socket clientSocket) {
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
                    String message;
                    while ((message = reader.readLine()) != null) {
                        updateUI("TCP:" + clientSocket.getInetAddress().getHostAddress(), message);
                    }
                } catch (IOException e) {
                    Log.d(this.getClass().getName(), e.toString());
                }
            }).start();
        }
    }

    private class UDPListener extends NetworkListener {
        private DatagramSocket udpSocket;

        public UDPListener(int port) {
            super();
            try {
                udpSocket = new DatagramSocket(port);
                udpSocket.getLocalPort();  // Set listen port after server socket is initialized
            } catch (IOException e) {
                Log.d(this.getClass().getName(), e.toString());
            }
        }

        public int getListenerPort() {
            return udpSocket.getLocalPort();
        }

        @Override
        public void stopListening() {
            super.stopListening();
            udpSocket.close();
        }

        @Override
        public void run() {
            try {
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (isRunning) {
                    udpSocket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    updateUI("UDP:" + packet.getAddress().getHostAddress(), message);
                }
            } catch (IOException e) {
                Log.d(this.getClass().getName(), e.toString());
            }
        }
    }

    @SuppressLint("MissingPermission")
    private class BluetoothListener extends NetworkListener {
        private final BluetoothAdapter bluetoothAdapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        private final BluetoothServerSocket bluetoothServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("Watchcat SPP", UUID.fromString(SPP_UUID));

        private BluetoothListener() throws IOException {
        }

        @Override
        public void run() {
            try {
                while (isRunning) {
                    BluetoothSocket socket = bluetoothServerSocket.accept();
                    handleBluetoothConnection(socket);
                }
            } catch (IOException e) {
                Log.d(this.getClass().getName(), e.toString());
            } finally {
                try {
                    if (bluetoothServerSocket != null) bluetoothServerSocket.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void handleBluetoothConnection(BluetoothSocket socket) {
            new Thread(() -> {
                try {
                    InputStream inputStream = socket.getInputStream();
                    byte[] buffer = new byte[1024];
                    int bytes;
                    while ((bytes = inputStream.read(buffer)) != -1) {
                        String message = new String(buffer, 0, bytes);
                        updateUI("SPP:" + socket.getRemoteDevice().getAddress(), message);
                    }
                } catch (IOException e) {
                    Log.d(this.getClass().getName(), e.toString());
                }
            }).start();
        }
    }

    @SuppressLint("MissingPermission")
    private class BluetoothLeListener extends NetworkListener {
        private final BluetoothLeAdvertiser bluetoothLeAdvertiser;
        private final BluetoothGattServer gattServer;
        private final BluetoothGattService uartService = new BluetoothGattService(UART_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Start advertising with a callback to handle events
        private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                // Successfully started advertising
                Log.d("BluetoothLeListener", "Advertising started successfully.");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                // Failed to start advertising
                Log.d("BluetoothLeListener", "Advertising failed with error code: " + errorCode);
            }
        };

        public BluetoothLeListener() {
            super();
            BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothLeAdvertiser = bluetoothManager.getAdapter().getBluetoothLeAdvertiser();
            gattServer = bluetoothManager.openGattServer(NetworkService.this, new BluetoothGattServerCallback() {
                @Override
                public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                    super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
                    // Handle incoming write requests here if needed
                    updateUI(device.getAlias(), new String(value));
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                }

                @Override
                public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                    if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                        Log.d("BluetoothLeListener", "Notifications enabled for " + descriptor.getCharacteristic().getUuid());
                    } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                        Log.d("BluetoothLeListener", "Notifications disabled for " + descriptor.getCharacteristic().getUuid());
                    }
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                }
            });
        }

        @Override
        public void run() {
            // Create TX Characteristic with proper permissions and properties
            BluetoothGattCharacteristic txCharacteristic = new BluetoothGattCharacteristic(
                    UART_TX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ);

            // Create RX Characteristic with proper permissions and properties
            BluetoothGattCharacteristic rxCharacteristic = new BluetoothGattCharacteristic(
                    UART_RX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);

            // Add CCCD descriptor for notifications
            BluetoothGattDescriptor txCccd = new BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // CCCD UUID
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            txCharacteristic.addDescriptor(txCccd);

            BluetoothGattDescriptor rxCccd = new BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // CCCD UUID
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            rxCharacteristic.addDescriptor(rxCccd);

            uartService.addCharacteristic(txCharacteristic);
            uartService.addCharacteristic(rxCharacteristic);

            gattServer.addService(uartService);

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true) // Allow connections to other devices
                    .build();

            AdvertiseData advertiseData = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();

            bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, advertiseCallback);
        }

        @Override
        public void stopListening() {
            super.stopListening();
            bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
            gattServer.removeService(uartService);
            gattServer.close();
        }
    }


    // Base class to handle thread control
    private abstract static class NetworkListener implements Runnable {
        protected boolean isRunning = true;

        public void stopListening() {
            isRunning = false;
        }
    }

    private int notifyId = 2;

    private void updateUI(String title, String message) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(notifyId++, notification);
    }

    private PendingIntent stopServicePendingIntent() {
        Intent stopIntent = new Intent(this, NetworkService.class);
        stopIntent.setAction("STOP_SERVICE");
        return PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
