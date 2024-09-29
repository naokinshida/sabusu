package com.example.nagoyameshi.form;

public class SubscriptionCancellationForm {
    private String cancelReason; // ここはIntegerではなくString型にする

    // GetterとSetter
    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }
}