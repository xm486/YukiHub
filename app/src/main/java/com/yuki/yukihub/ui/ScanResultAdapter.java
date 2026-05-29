package com.yuki.yukihub.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.yuki.yukihub.R;
import com.yuki.yukihub.scanner.ScanResult;

import java.util.List;

public class ScanResultAdapter extends RecyclerView.Adapter<ScanResultAdapter.Holder> {
    private final List<ScanResult> results;
    public ScanResultAdapter(List<ScanResult> results) { this.results = results; }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_scan_result, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder h, int p) {
        ScanResult r = results.get(p);
        h.title.setText(r.title);
        h.info.setText(r.engine.getDisplayName() + " · confidence " + r.confidence + " · " + r.uri);
    }
    @Override public int getItemCount() { return results.size(); }
    static class Holder extends RecyclerView.ViewHolder {
        TextView title, info;
        Holder(@NonNull View itemView) { super(itemView); title = itemView.findViewById(R.id.tvScanGameTitle); info = itemView.findViewById(R.id.tvScanInfo); }
    }
}