namespace ServiceLib.Tests.Handler;

public class SubMigrationTests
{
    [Test]
    public async Task ReadUrls_TakesOnlyWebLinksWithoutRepeats()
    {
        var path = Path.Combine(Path.GetTempPath(), $"submig-{Guid.NewGuid():N}.db");
        try
        {
            using (var db = new SQLiteConnection(path))
            {
                db.CreateTable<SubItem>();
                db.Insert(new SubItem { Id = "1", Remarks = "a", Url = "https://provider.example/sub/abc" });
                db.Insert(new SubItem { Id = "2", Remarks = "b", Url = " https://provider.example/sub/abc " });
                db.Insert(new SubItem { Id = "3", Remarks = "c", Url = "http://10.0.0.1:2096/sub/x" });
                db.Insert(new SubItem { Id = "4", Remarks = "d", Url = "" });
                db.Insert(new SubItem { Id = "5", Remarks = "e", Url = "vless://not-a-subscription" });
            }

            var urls = SubMigration.ReadUrls(path);

            await urls.Count.Should().BeEqualTo(2);
            await urls.Should().Contain("https://provider.example/sub/abc");
            await urls.Should().Contain("http://10.0.0.1:2096/sub/x");
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Test]
    public async Task ReadUrls_OfABrokenFileIsEmpty()
    {
        var path = Path.Combine(Path.GetTempPath(), $"submig-{Guid.NewGuid():N}.db");
        File.WriteAllText(path, "not a database");
        try
        {
            await SubMigration.ReadUrls(path).Count.Should().BeEqualTo(0);
        }
        finally
        {
            File.Delete(path);
        }
    }
}
