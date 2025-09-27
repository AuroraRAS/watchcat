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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class NetworkService extends Service {
    private static final String TAG = NetworkService.class.getSimpleName();
    private static NetworkService theService = null;
    private static final String CHANNEL_ID = "Watchcat_Channel";
    private static final int DEFAULT_PORT = 12719;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final String SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"; // Standard SPP UUID
    private static final UUID UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_TX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UART_RX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

    // Network listeners
    private TCPListener tcpListener;
    private UDPListener udpListener;
    private BluetoothListener bluetoothListener;
    private BluetoothLeListener bluetoothLeListener;
    private final AtomicInteger notificationCounter = new AtomicInteger(2);

    @Override
    public void onCreate() {
        super.onCreate();
        theService = this;
    }

    public NetworkService getService() {
        return theService;
    }

    public enum Listener {TCP, UDP, SSP, BLE}

    private boolean isValidPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    public synchronized void startListener(Listener service, int port) {
        stopListener(service);
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

    public synchronized void stopListener(Listener service) {
        switch (service) {
            case TCP:
                if (tcpListener != null) {
                    tcpListener.stopListening();
                }
                tcpListener = null;
                break;
            case UDP:
                if (udpListener != null) {
                    udpListener.stopListening();
                }
                udpListener = null;
                break;
            case SSP:
                if (bluetoothListener != null) {
                    bluetoothListener.stopListening();
                }
                bluetoothListener = null;
                break;
            case BLE:
                if (bluetoothLeListener != null) {
                    bluetoothLeListener.stopListening();
                }
                bluetoothLeListener = null;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + service);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.w(TAG, "Received null intent in onStartCommand");
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action == null) {
            Log.w(TAG, "Received intent without action in onStartCommand");
            return START_STICKY;
        }

        if ("START_SERVICE".equals(action)) {
            int tcpPort = intent.getIntExtra("TCPPORT", DEFAULT_PORT);
            int udpPort = intent.getIntExtra("UDPPORT", DEFAULT_PORT);
            createNotificationChannel();

            if (intent.getBooleanExtra("Listener.TCP", false)) {
                startListener(Listener.TCP, tcpPort);
            }
            if (intent.getBooleanExtra("Listener.UDP", false)) {
                startListener(Listener.UDP, udpPort);
            }
            if (intent.getBooleanExtra("Listener.SSP", false)) {
                startListener(Listener.SSP, 0);
            }
            if (intent.getBooleanExtra("Listener.BLE", false)) {
                startListener(Listener.BLE, 0);
            }

            StringBuilder contentBuilder = new StringBuilder();
            if (tcpListener != null) contentBuilder.append("TCP,");
            if (udpListener != null) contentBuilder.append("UDP,");
            if (bluetoothListener != null) contentBuilder.append("SSP,");
            if (bluetoothLeListener != null) contentBuilder.append("BLE,");

            int length = contentBuilder.length();
            if (length > 0 && contentBuilder.charAt(length - 1) == ',') {
                contentBuilder.deleteCharAt(length - 1);
            }

            StringBuilder portBuilder = new StringBuilder();
            if (tcpListener != null) {
                portBuilder.append("T:").append(tcpListener.getListenerPort());
            }
            if (udpListener != null) {
                if (portBuilder.length() > 0) {
                    portBuilder.append(", ");
                }
                portBuilder.append("U:").append(udpListener.getListenerPort());
            }

            if (portBuilder.length() > 0) {
                if (contentBuilder.length() > 0) {
                    contentBuilder.append('\n');
                }
                contentBuilder.append(portBuilder);
            }

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("LISTENING on:")
                    .setContentText(contentBuilder.toString())
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(contentBuilder.toString()))
                    .addAction(R.mipmap.ic_launcher, "Stop Service", stopServicePendingIntent())
                    .build();

            startForeground(1, notification);
        } else if ("STOP_SERVICE".equals(action)) {
            stopListener(Listener.TCP);
            stopListener(Listener.UDP);
            stopListener(Listener.SSP);
            stopListener(Listener.BLE);
            stopForeground(STOP_FOREGROUND_REMOVE);
            theService = null;
            stopSelf();
        }

        return START_STICKY;
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Watchcat Service Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        serviceChannel.setDescription("Notifications from Watchcat Service");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        } else {
            Log.w(TAG, "Notification manager is unavailable; channel not created");
        }
    }

    private void startTCPListener(int port) {
        if (!isValidPort(port)) {
            Log.w(TAG, "Invalid TCP port requested: " + port);
            return;
        }
        try {
            TCPListener listener = new TCPListener(port);
            tcpListener = listener;
            new Thread(listener, "Watchcat-TCPListener").start();
        } catch (IOException e) {
            Log.w(TAG, "Unable to start TCP listener", e);
            tcpListener = null;
        }
    }

    private void startUDPListener(int port) {
        if (!isValidPort(port)) {
            Log.w(TAG, "Invalid UDP port requested: " + port);
            return;
        }
        try {
            UDPListener listener = new UDPListener(port);
            udpListener = listener;
            new Thread(listener, "Watchcat-UDPListener").start();
        } catch (IOException e) {
            Log.w(TAG, "Unable to start UDP listener", e);
            udpListener = null;
        }
    }

    private void startBluetoothListener() {
        try {
            BluetoothListener listener = new BluetoothListener();
            bluetoothListener = listener;
            new Thread(listener, "Watchcat-SPPListener").start();
        } catch (IOException e) {
            Log.w(TAG, "Unable to start Bluetooth SPP listener", e);
            bluetoothListener = null;
        }
    }

    private void startBluetoothLeListener() {
        try {
            BluetoothLeListener listener = new BluetoothLeListener();
            bluetoothLeListener = listener;
            new Thread(listener, "Watchcat-BLEListener").start();
        } catch (IllegalStateException e) {
            Log.w(TAG, "Unable to start Bluetooth LE listener", e);
            bluetoothLeListener = null;
        }
    }

    private class TCPListener extends NetworkListener {
        private final ServerSocket serverSocket;

        public TCPListener(int port) throws IOException {
            super();
            this.serverSocket = new ServerSocket(port);
        }

        public int getListenerPort() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void stopListening() {
            super.stopListening();
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing TCP server socket", e);
            }
        }

        @Override
        public void run() {
            while (isRunning) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    if (clientSocket != null) {
                        handleTCPConnection(clientSocket);
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        Log.w(TAG, "TCP listener encountered an error", e);
                    }
                    break;
                }
            }
        }

        private void handleTCPConnection(Socket clientSocket) {
            Thread thread = new Thread(() -> {
                try (Socket socket = clientSocket;
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    String message;
                    while ((message = reader.readLine()) != null) {
                        updateUI("TCP:" + socket.getInetAddress().getHostAddress(), message);
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        Log.w(TAG, "Error handling TCP connection", e);
                    }
                }
            }, "Watchcat-TCPClient");
            thread.start();
        }
    }

    private class UDPListener extends NetworkListener {
        private final DatagramSocket udpSocket;

        public UDPListener(int port) throws IOException {
            super();
            udpSocket = new DatagramSocket(port);
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
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (isRunning && !udpSocket.isClosed()) {
                try {
                    packet.setData(buffer);
                    packet.setLength(buffer.length);
                    udpSocket.receive(packet);
                    String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    updateUI("UDP:" + packet.getAddress().getHostAddress(), message);
                } catch (IOException e) {
                    if (isRunning) {
                        Log.w(TAG, "UDP listener encountered an error", e);
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private class BluetoothListener extends NetworkListener {
        private final BluetoothServerSocket bluetoothServerSocket;

        private BluetoothListener() throws IOException {
            super();
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager == null) {
                throw new IOException("Bluetooth manager unavailable");
            }
            BluetoothAdapter adapter = manager.getAdapter();
            if (adapter == null) {
                throw new IOException("Bluetooth adapter unavailable");
            }
            bluetoothServerSocket = adapter.listenUsingRfcommWithServiceRecord("Watchcat SPP", UUID.fromString(SPP_UUID));
        }

        @Override
        public void stopListening() {
            super.stopListening();
            try {
                bluetoothServerSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing Bluetooth SPP server socket", e);
            }
        }

        @Override
        public void run() {
            while (isRunning) {
                try {
                    BluetoothSocket socket = bluetoothServerSocket.accept();
                    if (socket != null) {
                        handleBluetoothConnection(socket);
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        Log.w(TAG, "Bluetooth SPP listener encountered an error", e);
                    }
                    break;
                }
            }
        }

        private void handleBluetoothConnection(BluetoothSocket socket) {
            Thread thread = new Thread(() -> {
                InputStream inputStream = null;
                try {
                    inputStream = socket.getInputStream();
                    byte[] buffer = new byte[1024];
                    int bytes;
                    while ((bytes = inputStream.read(buffer)) != -1) {
                        String message = new String(buffer, 0, bytes, StandardCharsets.UTF_8);
                        updateUI("SPP:" + socket.getRemoteDevice().getAddress(), message);
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        Log.w(TAG, "Error handling Bluetooth SPP connection", e);
                    }
                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (IOException ignored) {
                        }
                    }
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }, "Watchcat-SPPClient");
            thread.start();
        }
    }

    @SuppressLint("MissingPermission")
    private class BluetoothLeListener extends NetworkListener {
        private final BluetoothLeAdvertiser bluetoothLeAdvertiser;
        private final BluetoothGattServer gattServer;
        private final BluetoothGattService uartService = new BluetoothGattService(UART_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                Log.d(TAG, "Bluetooth LE advertising started successfully.");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                Log.w(TAG, "Bluetooth LE advertising failed with error code: " + errorCode);
            }
        };

        public BluetoothLeListener() {
            super();
            BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager == null) {
                throw new IllegalStateException("Bluetooth manager unavailable");
            }
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter == null) {
                throw new IllegalStateException("Bluetooth adapter unavailable");
            }
            bluetoothLeAdvertiser = adapter.getBluetoothLeAdvertiser();
            if (bluetoothLeAdvertiser == null) {
                throw new IllegalStateException("Bluetooth LE advertising is not supported on this device");
            }
            gattServer = bluetoothManager.openGattServer(NetworkService.this, new BluetoothGattServerCallback() {
                @Override
                public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                    super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
                    if (value != null) {
                        updateUI(getDeviceAlias(device), new String(value, StandardCharsets.UTF_8));
                    }
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                }

                @Override
                public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                    if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                        Log.d(TAG, "Notifications enabled for " + descriptor.getCharacteristic().getUuid());
                    } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                        Log.d(TAG, "Notifications disabled for " + descriptor.getCharacteristic().getUuid());
                    }
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
                }
            });
            if (gattServer == null) {
                throw new IllegalStateException("Unable to open GATT server");
            }
        }

        @Override
        public void run() {
            BluetoothGattCharacteristic txCharacteristic = new BluetoothGattCharacteristic(
                    UART_TX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ);

            BluetoothGattCharacteristic rxCharacteristic = new BluetoothGattCharacteristic(
                    UART_RX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_WRITE);

            BluetoothGattDescriptor txCccd = new BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            txCharacteristic.addDescriptor(txCccd);

            BluetoothGattDescriptor rxCccd = new BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            rxCharacteristic.addDescriptor(rxCccd);

            uartService.addCharacteristic(txCharacteristic);
            uartService.addCharacteristic(rxCharacteristic);

            gattServer.addService(uartService);

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build();

            AdvertiseData advertiseData = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();

            bluetoothLeAdvertiser.startAdvertising(settings, advertiseData, advertiseCallback);
        }

        @Override
        public void stopListening() {
            super.stopListening();
            try {
                bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
            } catch (IllegalStateException e) {
                Log.w(TAG, "Failed to stop Bluetooth LE advertising", e);
            }
            try {
                gattServer.removeService(uartService);
            } catch (IllegalArgumentException ignored) {
            }
            gattServer.close();
        }
    }


    // Base class to handle thread control
    private abstract static class NetworkListener implements Runnable {
        protected volatile boolean isRunning = true;

        public void stopListening() {
            isRunning = false;
        }
    }

    private void updateUI(String title, String message) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .build();

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(notificationCounter.getAndIncrement(), notification);
        } else {
            Log.w(TAG, "Notification manager unavailable; unable to update UI");
        }
    }

    private String getDeviceAlias(@Nullable BluetoothDevice device) {
        if (device == null) {
            return "Unknown device";
        }
        try {
            String alias = device.getAlias();
            if (alias != null && !alias.isEmpty()) {
                return alias;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to access Bluetooth device alias", e);
        }
        String name = device.getName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return device.getAddress();
    }

    private PendingIntent stopServicePendingIntent() {
        Intent stopIntent = new Intent(this, NetworkService.class);
        stopIntent.setAction("STOP_SERVICE");
        return PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        theService = null;
    }
}
