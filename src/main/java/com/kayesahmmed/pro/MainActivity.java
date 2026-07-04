package com.kayesahmmed.pro;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private EditText etUsername, etPassword;
    private Button btnLogin;
    private TextView tvStatus, tvWelcome;
    private LinearLayout loginForm;

    private FirebaseDatabase _firebase;
    private DatabaseReference updateRef, userRef;
    private SharedPreferences save, KEY, sp;
    private ProgressDialog prog;
    private AlertDialog dg;

    private boolean loginInProgress = false;
    private boolean isDialogShowing = false;
    private boolean keyExpiredDialogShowing = false;

    private String app_version = "";
    private Timer _timer = new Timer();
    private String link = "", message = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // UI initialize
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        tvStatus = findViewById(R.id.tv_status);
        tvWelcome = findViewById(R.id.tv_welcome);
        loginForm = findViewById(R.id.login_form);

        // Firebase initialize
        FirebaseApp.initializeApp(this);
        _firebase = FirebaseDatabase.getInstance();
        userRef = _firebase.getReference("User");
        updateRef = _firebase.getReference("update");

        // SharedPreferences
        save = getSharedPreferences("save", MODE_PRIVATE);
        KEY = getSharedPreferences("KEY", MODE_PRIVATE);
        sp = getSharedPreferences("data", MODE_PRIVATE);

        // Check saved credentials
        if (!save.getString("edittext1", "").equals("") && !save.getString("edittext2", "").equals("")) {
            etUsername.setText(save.getString("edittext1", ""));
            etPassword.setText(save.getString("edittext2", ""));
        }

        // Check cached version
        String cachedVersion = sp.getString("cached_app_version", "");
        if (!cachedVersion.equals("")) {
            app_version = cachedVersion;
        }

        // Firebase Update Check (Realtime)
        updateRef.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                handleUpdate(dataSnapshot);
            }
            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                handleUpdate(dataSnapshot);
            }
            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {}
            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {}
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });

        // Login Button Click
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performLogin();
            }
        });

        // Start version check immediately
        checkAppVersion();
    }

    // ---------- UPDATE HANDLER ----------
    private void handleUpdate(DataSnapshot dataSnapshot) {
        if (!dataSnapshot.getKey().equals("up")) return;

        HashMap<String, Object> map = (HashMap<String, Object>) dataSnapshot.getValue();
        if (map != null && map.containsKey("version")) {
            app_version = map.get("version").toString();
            sp.edit().putString("cached_app_version", app_version).apply();

            if (map.containsKey("link")) link = map.get("link").toString();
            if (map.containsKey("message")) message = map.get("message").toString();

            if (!ModXLab().equals(app_version)) {
                showUpdateDialog(app_version);
            } else {
                Toast.makeText(MainActivity.this, "App is up to date", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void checkAppVersion() {
        String cached = sp.getString("cached_app_version", "");
        if (!cached.equals("") && !cached.equals(ModXLab())) {
            showUpdateDialog(cached);
        }
    }

    public String ModXLab() {
        try {
            android.content.pm.PackageInfo pinfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pinfo.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- LOGIN LOGIC ----------
    private void performLogin() {
        if (loginInProgress) return;
        loginInProgress = true;
        btnLogin.setEnabled(false);

        if (!isNetworkAvailable()) {
            loginInProgress = false;
            btnLogin.setEnabled(true);
            Toast.makeText(this, "Connection Error !", Toast.LENGTH_SHORT).show();
            return;
        }

        final String inputUser = etUsername.getText().toString().trim();
        final String inputPass = etPassword.getText().toString().trim();

        if (inputUser.equals("") || inputPass.equals("")) {
            loginInProgress = false;
            btnLogin.setEnabled(true);
            Toast.makeText(this, "Please Fill Details", Toast.LENGTH_SHORT).show();
            return;
        }

        save.edit().putString("edittext1", inputUser).putString("edittext2", inputPass).apply();

        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                loginInProgress = false;
                btnLogin.setEnabled(true);
                if (isFinishing() || isDestroyed()) return;

                HashMap<String, Object> matchedUser = null;

                for (DataSnapshot child : dataSnapshot.getChildren()) {
                    HashMap<String, Object> map = (HashMap<String, Object>) child.getValue();
                    if (map == null) continue;

                    Object u = map.get("user");
                    Object p = map.get("pass");
                    if (u != null && p != null && inputUser.equals(u.toString()) && inputPass.equals(p.toString())) {
                        matchedUser = map;
                        break;
                    }
                }

                if (matchedUser == null) {
                    Toast.makeText(MainActivity.this, "Invalid Username or Password!", Toast.LENGTH_SHORT).show();
                    return;
                }

                Object statusObj = matchedUser.get("status");
                Object timeObj = matchedUser.get("time");

                if (statusObj == null || timeObj == null) {
                    Toast.makeText(MainActivity.this, "Account Error!", Toast.LENGTH_SHORT).show();
                    return;
                }

                boolean isExpired = false;
                try {
                    long expireTime = (long) Double.parseDouble(timeObj.toString());
                    if (System.currentTimeMillis() > expireTime) isExpired = true;
                } catch (Exception ignored) {}

                if (!statusObj.toString().equals("true") || isExpired) {
                    if (keyExpiredDialogShowing) return;
                    keyExpiredDialogShowing = true;
                    showKeyExpiredDialog();
                    return;
                }

                // Save user data to KEY SharedPreferences
                KEY.edit()
                    .putString("User", matchedUser.get("user").toString())
                    .putString("Status", matchedUser.get("status").toString())
                    .putString("Register", matchedUser.get("rgtime").toString())
                    .putString("time", matchedUser.get("time").toString())
                    .putString("Valid", matchedUser.get("Validity").toString())
                    .putString("key", matchedUser.get("key").toString())
                    .apply();

                Toast.makeText(MainActivity.this, "Login Success", Toast.LENGTH_SHORT).show();
                showComponentDialog();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                loginInProgress = false;
                btnLogin.setEnabled(true);
                Toast.makeText(MainActivity.this, "Connection Error !", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- DIALOGS ----------
	private void showUpdateDialog(final String version) {
        if (isDialogShowing) return;
        isDialogShowing = true;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Update Available !");
        builder.setIcon(android.R.drawable.ic_dialog_info);
        builder.setMessage("A new version ( " + version + " ) is available.\nPlease update the app to continue.");
        builder.setCancelable(false);
        builder.setPositiveButton("UPDATE", null);
        builder.setNegativeButton("EXIT", null);

        final AlertDialog dialog = builder.create();
        dialog.show();

        // Style dialog background
        try {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(60f);
            dialog.getWindow().setBackgroundDrawable(bg);
        } catch (Exception ignored) {}

        // ✅ AIDE তে ব্যবহারের জন্য link এর একটি final কপি তৈরি করুন
        final String finalLink = (link != null && !link.equals("")) ? link : "https://t.me/kayesahmmedpro";

        // Positive Button (Update)
        final Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positiveBtn != null) {
            GradientDrawable btnShape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF229ED9, 0xFF5ABEF0});
            btnShape.setCornerRadius(100f);
            positiveBtn.setBackground(new RippleDrawable(null, btnShape, null));
            positiveBtn.setTextColor(Color.WHITE);
            positiveBtn.setAllCaps(false);

            positiveBtn.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(final View v) {
						v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
							.withEndAction(new Runnable() {
								@Override
								public void run() {
									v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
									isDialogShowing = false;
									dialog.dismiss();
									try {
										// ✅ এখানে link এর বদলে finalLink ব্যবহার করা হয়েছে
										startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(finalLink)));
									} catch (Exception e) {
										Toast.makeText(MainActivity.this, "Could not open link!", Toast.LENGTH_SHORT).show();
									}
								}
							}).start();
					}
				});
        }

        // Negative Button (Exit)
        final Button negativeBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negativeBtn != null) {
            negativeBtn.setTextColor(Color.parseColor("#FF5252"));
            negativeBtn.setAllCaps(false);
            negativeBtn.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						isDialogShowing = false;
						dialog.dismiss();
						finishAffinity();
					}
				});
        }
    }

        
    private void showKeyExpiredDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Key Expired");
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setMessage("Your key has expired.\nPlease contact administrator to renew your access.");
        builder.setCancelable(true);
        builder.setPositiveButton("CONTACT", null);
        builder.setNegativeButton("CANCEL", null);

        final AlertDialog dialog = builder.create();
        dialog.show();

        // Style dialog background
        try {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(60f);
            dialog.getWindow().setBackgroundDrawable(bg);
        } catch (Exception ignored) {}

        final Button positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positiveBtn != null) {
            GradientDrawable btnShape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF229ED9, 0xFF5ABEF0});
            btnShape.setCornerRadius(100f);
            positiveBtn.setBackground(new RippleDrawable(null, btnShape, null));
            positiveBtn.setTextColor(Color.WHITE);
            positiveBtn.setAllCaps(false);
            positiveBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    keyExpiredDialogShowing = false;
                    dialog.dismiss();
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/kayesahmmedpro")));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Could not open Telegram!", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        final Button negativeBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negativeBtn != null) {
            negativeBtn.setTextColor(Color.parseColor("#FF5252"));
            negativeBtn.setAllCaps(false);
            negativeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    keyExpiredDialogShowing = false;
                    dialog.dismiss();
                }
            });
        }
    }

    // ---------- LOADING & COMPONENT DIALOG ----------
    private void showLoadingDialog(boolean show, String title) {
        if (show) {
            if (prog == null) {
                prog = new ProgressDialog(this);
                prog.setMax(100);
                prog.setIndeterminate(true);
                prog.setCancelable(false);
                prog.setCanceledOnTouchOutside(false);
            }
            prog.setMessage(title);
            prog.show();
        } else {
            if (prog != null) prog.dismiss();
        }
    }

    private void showComponentDialog() {
        if (Settings.canDrawOverlays(MainActivity.this)) {
            showLoadingDialog(true, "Verifying With Server...");
            _timer = new Timer();
            _timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showLoadingDialog(false, "Verifying With Server...");
                            // Success: Show welcome text
                            loginForm.setVisibility(View.GONE);
                            tvWelcome.setVisibility(View.VISIBLE);
                            Toast.makeText(MainActivity.this, "Welcome to ModX Lab Pro!", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }, 3000);
        } else {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }
    }

    // ---------- UTILITY ----------
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnectedOrConnecting();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
                  }

