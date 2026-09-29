package com.angel.despiertameaqui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.angel.despiertameaqui.ui.theme.DespiertameAquiTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DespiertameAquiTheme {
                MainScreen()
            }
        }
    }
}

private enum class TripState { IDLE, RUNNING, PAUSED }

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val locationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var selectedLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var radius by remember { mutableStateOf(1000f) }
    var isBatterySaving by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Toca el mapa o busca un lugar") }
    val tripPreferences = remember {
        context.getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var tripState by remember {
        mutableStateOf(
            when (tripPreferences.getString(LocationService.PREF_STATE, "idle")) {
                "running" -> TripState.RUNNING
                "paused" -> TripState.PAUSED
                else -> TripState.IDLE
            }
        )
    }
    val currentTripState by rememberUpdatedState(tripState)

    var locationPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Refresh permission state when activity is resumed
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Escuchar actualizaciones del servicio para mostrar distancia y tiempo
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getBooleanExtra("trip_finished", false) == true) {
                    tripState = TripState.IDLE
                }
                statusText = intent?.getStringExtra("status_text") ?: "Siguiendo destino..."
            }
        }
        val filter = IntentFilter("LOCATION_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Note: ACCESS_BACKGROUND_LOCATION is not added here because on Android 11+
    // it must be requested separately from foreground permissions.
    // The Foreground Service with type "location" will still work for our tracking.

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val fineGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationPermissionGranted = fineGranted || coarseGranted

        if (!locationPermissionGranted) {
            Toast.makeText(context, "Permisos necesarios para funcionar correctamente", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        launcher.launch(permissions.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    fun centerMapOnUser() {
        if (!locationPermissionGranted) {
            launcher.launch(permissions.toTypedArray())
            Toast.makeText(context, "Permite el acceso a tu ubicación", Toast.LENGTH_SHORT).show()
            return
        }

        statusText = "Buscando tu ubicación..."
        locationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { location ->
            if (location != null) {
                val userPoint = GeoPoint(location.latitude, location.longitude)
                mapView?.controller?.animateTo(userPoint)
                mapView?.controller?.setZoom(16.0)
                statusText = "Mapa centrado en tu ubicación"
            } else {
                Toast.makeText(context, "Activa la ubicación del teléfono", Toast.LENGTH_LONG).show()
                statusText = "No se pudo obtener tu ubicación"
            }
        }.addOnFailureListener {
            Toast.makeText(context, "No se pudo obtener tu ubicación", Toast.LENGTH_LONG).show()
            statusText = "Error al obtener la ubicación"
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Despiértame Aquí", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Buscador de sitios
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Buscar destino (ej: San José)") },
                trailingIcon = {
                    IconButton(onClick = {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        try {
                            val addresses = geocoder.getFromLocationName(searchText, 1)
                            if (addresses?.isNotEmpty() == true) {
                                val location = addresses[0]
                                val point = GeoPoint(location.latitude, location.longitude)
                                selectedLocation = point
                                mapView?.controller?.animateTo(point)
                                mapView?.controller?.setZoom(15.0)
                                statusText = "Destino seleccionado: ${location.featureName ?: ""}"
                            } else {
                                Toast.makeText(context, "No se encontró el lugar", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error en la búsqueda", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Buscar")
                    }
                },
                shape = RoundedCornerShape(12.dp),
                enabled = tripState == TripState.IDLE,
                singleLine = true
            )

            // Mapa Principal
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // OSM blocks generic SDK identifiers. This stable, app-specific
                        // identifier complies with its public tile usage policy.
                        Configuration.getInstance().userAgentValue =
                            "DespiertameAqui/1.3 (Android; ${ctx.packageName})"
                        MapView(ctx).apply {
                            setMultiTouchControls(true)
                            controller.setZoom(12.0)
                            controller.setCenter(GeoPoint(9.9333, -84.0833))
                            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                                    if (currentTripState != TripState.IDLE) {
                                        Toast.makeText(ctx, "Termina el viaje para cambiar el destino", Toast.LENGTH_SHORT).show()
                                        return true
                                    }
                                    selectedLocation = point
                                    statusText = "Destino marcado en el mapa"
                                    return true
                                }

                                override fun longPressHelper(point: GeoPoint) = false
                            }))
                            mapView = this
                        }
                    },
                    update = { map ->
                        map.overlays.removeAll { it is Marker || it is Polygon }
                        selectedLocation?.let { point ->
                            map.overlays.add(Polygon().apply {
                                points = Polygon.pointsAsCircle(point, radius.toDouble())
                                fillPaint.color = android.graphics.Color.argb(34, 33, 150, 243)
                                outlinePaint.color = android.graphics.Color.BLUE
                                outlinePaint.strokeWidth = 3f
                            })
                            map.overlays.add(Marker(map).apply {
                                position = point
                                title = "Tu destino"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            })
                        }
                        map.invalidate()
                    }
                )

                DisposableEffect(mapView) {
                    mapView?.onResume()
                    onDispose { mapView?.onPause() }
                }

                // Overlay de información (Distancia y Tiempo Estimado)
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Text(
                        statusText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                FloatingActionButton(
                    onClick = { centerMapOnUser() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ) {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = "Centrar en mi ubicación"
                    )
                }
            }

            // Panel de Control inferior
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Avisarme cuando esté a: ${if(radius >= 1000) "${(radius/1000).toInt()}km" else "${radius.toInt()}m"}",
                        style = MaterialTheme.typography.titleSmall
                    )

                    RadiusSelector(radius, tripState == TripState.IDLE) { radius = it }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Ahorro de batería", style = MaterialTheme.typography.bodyLarge)
                            Text("Actualiza ubicación menos frecuente", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = isBatterySaving,
                            onCheckedChange = { isBatterySaving = it },
                            enabled = tripState == TripState.IDLE
                        )
                    }

                    if (tripState == TripState.IDLE) {
                        Button(
                            onClick = {
                                if (selectedLocation != null) {
                                    val intent = Intent(context, LocationService::class.java).apply {
                                        putExtra("target_lat", selectedLocation!!.latitude)
                                        putExtra("target_lng", selectedLocation!!.longitude)
                                        putExtra("radius", radius)
                                        putExtra("battery_saving", isBatterySaving)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    tripState = TripState.RUNNING
                                    tripPreferences.edit().putString(LocationService.PREF_STATE, "running").apply()
                                    statusText = "Viaje iniciado. Calculando..."
                                    Toast.makeText(context, "¡Alarma activada!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Por favor, marca un punto en el mapa", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("COMENZAR VIAJE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    val action = if (tripState == TripState.RUNNING) {
                                        LocationService.ACTION_PAUSE
                                    } else {
                                        LocationService.ACTION_RESUME
                                    }
                                    context.startService(Intent(context, LocationService::class.java).setAction(action))
                                    tripState = if (tripState == TripState.RUNNING) TripState.PAUSED else TripState.RUNNING
                                    tripPreferences.edit().putString(
                                        LocationService.PREF_STATE,
                                        if (tripState == TripState.PAUSED) "paused" else "running"
                                    ).apply()
                                    statusText = if (tripState == TripState.PAUSED) "Viaje pausado" else "Viaje reanudado. Calculando..."
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    if (tripState == TripState.RUNNING) "PAUSAR" else "REANUDAR",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    context.startService(
                                        Intent(context, LocationService::class.java)
                                            .setAction(LocationService.ACTION_STOP)
                                    )
                                    tripState = TripState.IDLE
                                    tripPreferences.edit().putString(LocationService.PREF_STATE, "idle").apply()
                                    statusText = "Viaje terminado. Puedes cambiar el destino"
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("TERMINAR", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RadiusSelector(currentRadius: Float, enabled: Boolean, onRadiusChange: (Float) -> Unit) {
    val distances = listOf(500f, 1000f, 2000f, 5000f, 10000f)
    val labels = listOf("500m", "1km", "2km", "5km", "10km")
    val currentIndex = distances.indexOf(currentRadius).coerceAtLeast(0)

    Column {
        Slider(
            value = currentIndex.toFloat(),
            enabled = enabled,
            onValueChange = { index ->
                onRadiusChange(distances[index.toInt()])
            },
            valueRange = 0f..(distances.size - 1).toFloat(),
            steps = distances.size - 2
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEachIndexed { index, label ->
                Text(
                    label,
                    fontSize = 11.sp,
                    color = if(index == currentIndex) MaterialTheme.colorScheme.primary else Color.Gray,
                    fontWeight = if(index == currentIndex) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
