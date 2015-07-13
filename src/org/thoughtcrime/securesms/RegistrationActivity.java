package org.thoughtcrime.securesms;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.AlertDialogWrapper;
//import com.google.android.gms.common.ConnectionResult;
//import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.i18n.phonenumbers.AsYouTypeFormatter;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import org.thoughtcrime.securesms.util.Dialogs;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.textsecure.api.util.PhoneNumberFormatter;

/**
 * The register account activity.  Prompts ths user for their registration information
 * and begins the account registration process.
 *
 * @author Moxie Marlinspike
 *
 */
public class RegistrationActivity extends BaseActionBarActivity {
  private TextView             handle;
  private Button               createButton;
  private Button               skipButton;

  private MasterSecret masterSecret;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    setContentView(R.layout.registration_activity);

    getSupportActionBar().setTitle(getString(R.string.RegistrationActivity_connect_with_textsecure));

    initializeResources();
  }

  private void initializeResources() {
    this.masterSecret   = getIntent().getParcelableExtra("master_secret");
    this.handle         = (TextView)findViewById(R.id.handle);
    this.createButton   = (Button)findViewById(R.id.registerButton);
    this.skipButton     = (Button)findViewById(R.id.skipButton);

    this.createButton.setOnClickListener(new CreateButtonListener());
    this.skipButton.setOnClickListener(new CancelButtonListener());

    if (getIntent().getBooleanExtra("cancel_button", false)) {
      this.skipButton.setVisibility(View.VISIBLE);
    } else {
      this.skipButton.setVisibility(View.INVISIBLE);
    }

    /*findViewById(R.id.twilio_shoutout).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setData(Uri.parse("https://twilio.com"));
        startActivity(intent);
      }
  });*/
  }

  private class CreateButtonListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      final RegistrationActivity self = RegistrationActivity.this;

      if (TextUtils.isEmpty(handle.getText())) {
        Toast.makeText(self,
                       getString(R.string.RegistrationActivity_you_must_specify_a_handle),
                       Toast.LENGTH_LONG).show();
        return;
      }

	  Intent intent = new Intent(self, RegistrationProgressActivity.class);
	  intent.putExtra("e164number", handle.getText().toString());
	  intent.putExtra("master_secret", masterSecret);
	  startActivity(intent);
	  finish();
    }
  }

  private class CancelButtonListener implements View.OnClickListener {
    @Override
    public void onClick(View v) {
      TextSecurePreferences.setPromptedPushRegistration(RegistrationActivity.this, true);
      Intent nextIntent = getIntent().getParcelableExtra("next_intent");

      if (nextIntent == null) {
        nextIntent = new Intent(RegistrationActivity.this, ConversationListActivity.class);
      }

      startActivity(nextIntent);
      finish();
    }
  }
}
