# 1. Definição das categorias e palavras-chave para o classificador
$categories = @(
    @{ name = "esporte"; keywords = "futebol, basquete, corrida, olimpiadas, treinos" },
    @{ name = "tecnologia"; keywords = "programacao, inteligencia artificial, hardware, gadgets, software" },
    @{ name = "culinaria"; keywords = "receitas, gastronomia, temperos, sobremesas, chefs" },
    @{ name = "noticias"; keywords = "politica, economia, mundo, cotidiano, reportagens" },
    @{ name = "viagens"; keywords = "destinos, passagens, hoteis, roteiros, mochilao" },
    @{ name = "ciencia"; keywords = "astronomia, fisica, biologia, quimica, experimentos" }
)

$count = 1000
$allPages = 1..$count | ForEach-Object { "page$_.com" }

Write-Host "Gerando 1.000 páginas com 1.000 links cada..."

$results = 1..$count | ForEach-Object {
    $i = $_
    # Seleciona uma categoria aleatória
    $cat = $categories[(Get-Random -Maximum $categories.Count)]
    $pageName = "page$i.com"
    $text = "Esta e a pagina $i sobre $($cat.name). Focada em: $($cat.keywords)."
    
    # Embaralha a lista global de 1.000 páginas para esta linha específica
    $shuffledLinks = $allPages | Get-Random -Count $count
    $linksStr = $shuffledLinks -join ","
    
    # Retorna a linha formatada
    "$pageName;$text;$linksStr"
}

# Salva no arquivo CSV esperado pelo DataServer
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllLines("$(Get-Location)\mock_internet.csv", $results, $utf8NoBom)

Write-Host "Concluído! Arquivo 'mock_internet.csv' gerado com sucesso."
