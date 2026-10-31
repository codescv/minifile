package moe.shizuku.api

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * Wire-compatible Parcelable container matching Shizuku's `moe.shizuku.api.BinderContainer`
 * so `MiniFileShizukuProvider` can receive and share `IShizukuService` binders with zero external dependencies.
 */
class BinderContainer(
    @JvmField var binder: IBinder?,
) : Parcelable {

    constructor(parcel: Parcel) : this(parcel.readStrongBinder())

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStrongBinder(binder)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BinderContainer> = object : Parcelable.Creator<BinderContainer> {
            override fun createFromParcel(source: Parcel): BinderContainer = BinderContainer(source)
            override fun newArray(size: Int): Array<BinderContainer?> = arrayOfNulls(size)
        }
    }
}
