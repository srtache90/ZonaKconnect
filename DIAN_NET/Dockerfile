FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
WORKDIR /src

COPY DIAN_NET/DIAN_NET/ .

RUN sed -i 's/Microsoft.NET.Sdk.WindowsDesktop/Microsoft.NET.Sdk/g; s/net8.0-windows/net8.0/g; s/<UseWPF>true<\/UseWPF>/<UseWPF>false<\/UseWPF>/g; s/<OutputType>WinExe<\/OutputType>/<OutputType>Exe<\/OutputType>/g' DIAN_NET.csproj \
    && rm -f MainWindow.xaml MainWindow.xaml.cs App.xaml App.xaml.cs \
    && rm -rf Properties

RUN dotnet restore DIAN_NET.csproj \
    && dotnet publish DIAN_NET.csproj -c Release -o /out --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:8.0
WORKDIR /app
COPY --from=build /out .
ENV ASPNETCORE_URLS=http://+:8080
EXPOSE 8080
ENTRYPOINT ["dotnet", "DIAN_NET.dll"]
