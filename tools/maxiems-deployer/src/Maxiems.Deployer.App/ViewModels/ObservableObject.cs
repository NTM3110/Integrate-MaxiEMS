using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace Maxiems.Deployer.App.ViewModels;

public abstract class ObservableObject : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool SetProperty<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }
        field = value;
        this.PropertyChanged?.Invoke(this, new(propertyName));
        return true;
    }

    protected void Notify([CallerMemberName] string? propertyName = null) =>
        this.PropertyChanged?.Invoke(this, new(propertyName));
}
