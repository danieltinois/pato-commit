import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

const foundations = [
  ["Next.js", "16.3.6 (Active LTS), App Router, TypeScript strict"],
  ["UI", "Tailwind CSS 4, shadcn/ui"],
  ["API", "Spring Boot 4.1.1, Java 25 LTS"],
  ["Database", "PostgreSQL 18, schema owned by Flyway"],
] as const;

export default function Home() {
  return (
    <main className="mx-auto w-full max-w-2xl flex-1 px-6 py-16">
      <h1 className="text-3xl font-semibold tracking-tight">Pato Commit</h1>
      <p className="mt-2 text-muted-foreground">
        What is happening across your GitHub repositories, and what needs you now.
      </p>

      <Card className="mt-10">
        <CardHeader>
          <CardTitle>Foundations in place</CardTitle>
          <CardDescription>
            Milestone M0. The Developer Inbox arrives in M3.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-3 sm:grid-cols-[9rem_1fr]">
            {foundations.map(([label, value]) => (
              <div key={label} className="contents">
                <dt className="text-sm font-medium">{label}</dt>
                <dd className="text-sm text-muted-foreground">{value}</dd>
              </div>
            ))}
          </dl>
        </CardContent>
      </Card>
    </main>
  );
}
