package info.nightscout.androidaps.plugins.general.automation.elements;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.logging.L;

public class Dropdown extends Element {
    private static Logger log = LoggerFactory.getLogger(L.AUTOMATION);
    ArrayList<String> items;


    public Dropdown(String name) {
        super();
        this.items.add(name);
    }

    public Dropdown(ArrayList<String> newList) {
        super();
        items = newList;
    }


    @Override
    public void addToLayout(LinearLayout root) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(root.getContext(),
                R.layout.spinner_centered, items);
        Spinner spinner = new Spinner(root.getContext());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        spinnerParams.setMargins(0, MainApp.dpToPx(4), 0, MainApp.dpToPx(4));
        spinner.setLayoutParams(spinnerParams);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setValue(position, listNames().get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spinner.setSelection(0);
        LinearLayout l = new LinearLayout(root.getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        l.addView(spinner);
        root.addView(l);
    }

    public Dropdown setValue(int pos, String value) {
        this.items.set(pos, value);
        return this;
    }

    public ArrayList<String> getValue() {
        return items;
    }

    public ArrayList<String> listNames(){
        return items;
    }


}